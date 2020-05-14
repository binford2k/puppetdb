(ns puppetlabs.puppetdb.config
  "Centralized place for reading a user-defined config INI file, validating,
   defaulting and converting into a format that can startup a PuppetDB instance.

   The schemas in this file define what is expected to be present in the INI file
   and the format expected by the rest of the application."
  (:import [java.security KeyStore]
           [org.joda.time Minutes Days Period]
           [java.util.regex PatternSyntaxException Pattern])
  (:require [puppetlabs.i18n.core :refer [trs]]
            [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.trapperkeeper.bootstrap :as tk-bootstrap]
            [clojure.java.io :as io]
            [me.raynes.fs :as fs]
            [clojure.string :as str]
            [schema.core :as s]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.time :as t]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.services :refer [service-id service-context]]))

(defn throw-cli-error [msg]
  (throw (ex-info msg {:type ::cli-error :message msg})))

(defn parse-git-section-name
  [s]
  "See the git-config(1) Syntax section.  Only allows subset for now..."
  ;; Match [section "subsection"], where the subsection is optional.
  (let [re #"\[([-0-9a-zA-Z]+)(?:[ \t]+([\p{IsLetter}\p{Digit}\p{Punct}\p{Space}]+))?\]"
        [_ section subtxt] (re-matches re s)]
    (when-not section
      (throw-cli-error (trs "error: invalid section name {0}" (pr-str s))))
    (if-not subtxt
      [section]
      (do
        (when-not (str/starts-with? subtxt "\"")
          (throw-cli-error
           (trs "error: config subsection {0} must start with a double-quote"
                (pr-str s))))
        (when-not (or (str/ends-with? subtxt "\""))
          (throw-cli-error
           (trs "error: config subsection {0} must end with an unescaped double-quote"
                (pr-str s))))
        (when-let [[_ backslashes] (re-find #"([\\]+)\"$" subtxt)]
          (when (odd? (count backslashes)) ;; Final " must be unescaped
            (throw-cli-error
             (trs "error: config subsection {0} must end with an unescaped double-quote"
                  (pr-str s)))))
        ;; Remove surrounding double-quotes
        (let [subtxt (subs subtxt 1 (dec (count subtxt)))]
          ;; Process escape chars.  Everything escaped just becomes itself.
          [section (str/replace subtxt #"(:?\\(.))" "$2")])))))

(defn coalesce-sections
  ;; FIXME: docs (mention "full match")

  [key-re config]
  ;; OK to follow git's convention here?  Assuming so, let's omit "."
  ;; from the section name for now since that's a deprecated git
  ;; config syntax.
  (reduce-kv
   (fn [result k v]
     (if-not (re-matches key-re (name k))
       (assoc result k v)
       (let [[sec subsec] (parse-git-section-name (str "[" (name k) "]"))
             sec (keyword sec)]
         (if subsec
           (do
             (when (get-in result [sec subsec])
               (throw-cli-error
                (trs "error: multiple [{0}] subsections in config file"
                     (pr-str (name k)))))
             (update-in result [sec subsec] ;; [keyword string]
                        (fn [prev]
                          (when prev
                            (throw-cli-error
                             (trs "error: multiple [{0}] sections in config file"
                                  (pr-str (name k)))))
                          v)))
           (do ;; no subsection
             (when-not (= sec k)
               (throw-cli-error
                (trs "error: parsed config section [{0}] incorrectly ({1} != {2}); please report"
                     (pr-str (name k)) (pr-str (name sec)) (pr-str (name k)))))
             (when (some keyword? (keys (k result)))
               (throw-cli-error
                (trs "error: multiple [{0}] sections in config file" (pr-str (name k)))))
             (update result k merge v))))))
   {}
   config))

(defn sectionwide-settings
  "Returns a map of the sectionwide settings in m."
  [m]
  (into {} (filter (fn [[k v]] (keyword? k)) m)))

(defn reduce-section
  "Behaves like reduce, but calls (f result name settings) for each
  subsection in the section.  If there are no subsections, calls (f
  result nil (sectionwide-settings section))."
  [f init section]
  (if-not (seq (filter string? (keys section)))
    (f init nil (sectionwide-settings section))
    (reduce-kv (fn [result k v]
                 (if (string? k)
                   (f result k v)
                   result))
               init
               section)))

(defn update-section-settings
  "First calls (f [] nil sectionwide-settings & args),
  where sectionwide-settings is a map of all of the keyword value
  pairs in m, and then calls (f [section] sectionwide-settings
  section-settings & args) for each string key (for each subsection)
  in m, where sectionwide-settings is be the result of the initial
  call to f described above, section is the string key (i.e. the
  subsection name), and section-settings is the map associated with
  that key (i.e. the subsection settings).  Merges and returns the
  results of the calls to f."
  [m f & args]
  (let [bad-key #(ex-info (trs "Unexpected key in config section {0}" %1)
                          {:kind ::unexpected-config-section-key
                           :key %
                           :map m})
        {:keys [sectionwide subsecs]} (group-by (fn [[k v]]
                                                  (cond
                                                    (string? k) :subsecs
                                                    (keyword? k) :sectionwide
                                                    :else (throw (bad-key k))))
                                                m)
        subsecs (into {} subsecs)
        sectionwide (apply f [] nil (into {} sectionwide) args)]
    (apply merge
           sectionwide
           (for [[subsec opts] subsecs]
             {subsec (apply f [subsec] sectionwide opts args)}))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

;; The config is currently broken into the sections that are defined
;; in the INI file. When the schema defaulting code gets changed to
;; support defaulting/converting nested maps, these configs can be put
;; together in a single schema that defines the config for PuppetDB

(defn warn-unknown-keys
  [schema data]
  (doseq [k (pls/unknown-keys schema data)]
    (log/warn
     (trs "The configuration item `{0}` does not exist and should be removed from the config." k))))

(defn warn-and-validate
  "Warns a user about unknown configurations items, removes them and validates the config."
  [schema data]
  (warn-unknown-keys schema data)
  (->> (pls/strip-unknown-keys schema data)
       (s/validate schema)))

(defn all-optional
  "Returns a schema map with all the keys made optional"
  [map-schema]
  (kitchensink/mapkeys s/optional-key map-schema))

(def per-database-config-in
  "Schema for incoming database config (user defined)"
  (all-optional
    {:conn-max-age (pls/defaulted-maybe s/Int 60)
     :conn-lifetime (s/maybe s/Int)
     :maximum-pool-size (pls/defaulted-maybe s/Int 25)
     :subname (s/maybe String)
     :user String
     :username String
     :password String
     :migrator-username String
     :migrator-password String
     :syntax_pgs String
     :read-only? (pls/defaulted-maybe String "false")
     :partition-conn-min (pls/defaulted-maybe s/Int 1)
     :partition-conn-max (pls/defaulted-maybe s/Int 25)
     :partition-count (pls/defaulted-maybe s/Int 1)
     :stats (pls/defaulted-maybe String "true")
     :log-statements (pls/defaulted-maybe String "true")
     :connection-timeout (pls/defaulted-maybe s/Int 3000)
     :facts-blacklist pls/Blacklist
     :facts-blacklist-type (pls/defaulted-maybe (s/enum "literal" "regex") "literal")
     :schema-check-interval (pls/defaulted-maybe s/Int (* 30 1000))
     ;; FIXME?
     ;; completely retired (ignored)
     :classname (pls/defaulted-maybe String "org.postgresql.Driver")
     :conn-keep-alive s/Int
     :log-slow-statements s/Int
     :statements-cache-size s/Int
     :subprotocol (pls/defaulted-maybe String "postgresql")}))

(def database-config-in
  (assoc per-database-config-in
         ;; FIXME: more restrictive spec for names, i.e. syntax check...
         ;; FIXME: this isn't right since we'll have ":database primary" at this point, not a string.
         s/Str per-database-config-in))

(def report-ttl-default "14d")

(def per-write-database-config-in
  "Includes the common database config params, also the write-db specific ones"
  (merge per-database-config-in
         (all-optional
           {:gc-interval (pls/defaulted-maybe s/Int 60)
            :report-ttl (pls/defaulted-maybe String report-ttl-default)
            :node-purge-ttl (pls/defaulted-maybe String "14d")
            :node-purge-gc-batch-limit (pls/defaulted-maybe s/Int 25)
            :node-ttl (pls/defaulted-maybe String "7d")
            :resource-events-ttl String
            :migrate (pls/defaulted-maybe String "true")})))

(def write-database-config-in
  (assoc per-write-database-config-in
         ;; FIXME: more restrictive spec for names, i.e. syntax check...
         s/Str per-write-database-config-in))

(def per-database-config-out
  "Schema for parsed/processed database config"
  {:subname String
   :conn-max-age Minutes
   :read-only? Boolean
   :partition-conn-min s/Int
   :partition-conn-max s/Int
   :partition-count s/Int
   :stats Boolean
   :log-statements Boolean
   :connection-timeout s/Int
   :maximum-pool-size s/Int
   (s/optional-key :conn-lifetime) (s/maybe Minutes)
   (s/optional-key :user) String
   (s/optional-key :username) String
   (s/optional-key :password) String
   (s/optional-key :migrator-username) String
   (s/optional-key :migrator-password) String
   (s/optional-key :syntax_pgs) String
   (s/optional-key :facts-blacklist) clojure.lang.PersistentVector
   :facts-blacklist-type String
   :schema-check-interval s/Int
   ;; completely retired (ignored)
   :classname String
   (s/optional-key :conn-keep-alive) Minutes
   (s/optional-key :log-slow-statements) Days
   (s/optional-key :statements-cache-size) s/Int
   :subprotocol String})

(def database-config-out
  (assoc per-database-config-out
         ;; FIXME: more restrictive spec for names, i.e. syntax check...
         s/Str per-database-config-out))

(def per-write-database-config-out
  "Schema for parsed/processed database config that includes write database params"
  (merge per-database-config-out
         {:gc-interval Minutes
          :report-ttl Period
          :node-purge-ttl Period
          :node-purge-gc-batch-limit (s/constrained s/Int (complement neg?))
          :node-ttl Period
          (s/optional-key :resource-events-ttl) Period
          :migrate Boolean}))

(def write-database-config-out
  (assoc per-write-database-config-out
         ;; FIXME: more restrictive spec for names, i.e. syntax check...
         s/Str per-write-database-config-out))

(defn half-the-cores*
  "Function for computing half the cores of the system, useful
   for testing."
  []
  (-> (kitchensink/num-cpus)
      (/ 2)
      int
      (max 1)))

(def half-the-cores
  "Half the number of CPU cores, used for defaulting the number of
   command processors"
  (half-the-cores*))

(defn default-max-command-size
  "Returns the max command size relative to the current max heap. This
  number was reached through testing of large catalogs and 1/205 was
  the largest catalog that could be processed without GC or out of
  memory errors"
  []
  (-> (Runtime/getRuntime)
      .maxMemory
      (/ 205)
      long))

(def command-processing-in
  "Schema for incoming command processing config (user defined) - currently incomplete"
  (all-optional
    {:threads (pls/defaulted-maybe s/Int half-the-cores)
     :max-command-size (pls/defaulted-maybe s/Int (default-max-command-size))
     :reject-large-commands (pls/defaulted-maybe String "false")
     :concurrent-writes (pls/defaulted-maybe s/Int (min half-the-cores 4))

     ;; Deprecated
     :max-frame-size (pls/defaulted-maybe s/Int 209715200)
     :store-usage s/Int
     :temp-usage s/Int
     :memory-usage s/Int}))

(def command-processing-out
  "Schema for parsed/processed command processing config - currently incomplete"
  {:threads s/Int
   :max-command-size s/Int
   :reject-large-commands Boolean
   :concurrent-writes s/Int

   ;; Deprecated
   :max-frame-size s/Int
   (s/optional-key :memory-usage) s/Int
   (s/optional-key :store-usage) s/Int
   (s/optional-key :temp-usage) s/Int})

(def puppetdb-config-in
  "Schema for validating the incoming [puppetdb] block"
  (all-optional
    {:certificate-whitelist s/Str
     :historical-catalogs-limit (pls/defaulted-maybe s/Int 0)
     :disable-update-checking (pls/defaulted-maybe String "false")
     :add-agent-report-filter (pls/defaulted-maybe String "true")}))

(def puppetdb-config-out
  "Schema for validating the parsed/processed [puppetdb] block"
  {(s/optional-key :certificate-whitelist) s/Str
   :historical-catalogs-limit s/Int
   :disable-update-checking Boolean
   :add-agent-report-filter Boolean})

(def developer-config-in
  (all-optional
   {:pretty-print (pls/defaulted-maybe String "false")
    :max-enqueued (pls/defaulted-maybe s/Int 1000000)}))

(def developer-config-out
  {:pretty-print Boolean
   :max-enqueued s/Int})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Database config

(defn validate-db-settings
  "Throws a {:type ::cli-error :message m} exception describing the
  required additions if the [database] configuration doesn't specify a
  subname."
  [{db-config :database :or {db-config {}} :as config}]
  (when (str/blank? (:subname db-config))
    (throw-cli-error (trs "No subname set in the [database] config.")))
  (let [{:keys [resource-events-ttl report-ttl]} db-config]
    (when (and resource-events-ttl
               (t/period-longer? (t/parse-period resource-events-ttl)
                                 (t/parse-period (or report-ttl report-ttl-default))))
      (throw-cli-error
       "The setting for resource-events-ttl must not be longer than report-ttl")))
  config)

(defn check-fact-regex [fact]
  (try
    (re-pattern fact)
    (catch PatternSyntaxException e
      (.getMessage e))))

(defn convert-blacklist-config
  "Validate and convert facts blacklist section of db-config to runtime format.
  Throws a {:type ::cli-error :message m} exception describing errors when compiling
  facts-blacklist regex patterns if :facts-blacklist-type is set to \"regex\"."
  [{db-config :database :or {db-config {}} :as config}]
  (if (= "regex" (:facts-blacklist-type db-config))
    (let [patts (->> db-config
                     :facts-blacklist
                     pls/blacklist->vector
                     (mapv check-fact-regex))]
      (when-let [errors (seq (filter string? patts))]
        (throw-cli-error
         (apply str (trs "Unable to parse facts-blacklist patterns:\n")
                 (interpose "\n" errors))))
      (assoc-in config [:database :facts-blacklist] patts))
    config))

(defn convert-section-config
  "validates and converts a `section-config` to `section-schema-out` using defaults from `section-schema-in`."
  [section-schema-in section-schema-out section-config]
  (->> section-config
       (warn-and-validate section-schema-in)
       (pls/defaulted-data section-schema-in)
       (pls/convert-to-schema section-schema-out)))

(defn configure-subsection
  [config section-schema-in section-schema-out]
  (->> (or config {})
       (convert-section-config section-schema-in section-schema-out)
       (s/validate section-schema-out)))

(defn configure-section
  [config section section-schema-in section-schema-out]
  (->> (configure-subsection (section config) section-schema-in section-schema-out)
       (assoc config section)))

(defn configure-section-with-subsections
  [config section section-schema-in section-schema-out]
  (update config section
          (fn [incoming-section-config] ;; i.e. {:subname ... "primary" { ...
            (update-section-settings
             incoming-section-config
             (fn [[subsec] sectionwide opts]
               ;; Adapt (configure-section doesn't know about subsections)
               (configure-section {(if subsec subsec section) opts}
                                  section
                                  section-schema-in
                                  section-schema-out))))))

(defn prefer-db-user-on-username-mismatch
  [{:keys [user username] :as config} db-name]
  ;; match puppetdb.jdbc/make-connection-pool
  (when (and user username (not= user username))
    (log/warn
     (if db-name
       (trs "Configured {0} database user {1} and username {2} don't match"
            (pr-str db-name) (pr-str user) (pr-str username))
       (trs "Configured database user {0} and username {1} don't match"
            (pr-str user) (pr-str username))))
    (log/warn
     (trs "Preferring configured user {0}" (pr-str user))))
  (let [config (update config :user #(or % (:username config)))]
    (assoc config :username (:user config))))

(defn default-events-ttl [config]
  (update config :resource-events-ttl #(or % (:report-ttl config))))

(defn ensure-migrator-info [config]
  ;; This expects to run after prefer-db-user-on-username-mismatch, so
  ;; the :user should always be the right answer.
  (assert (:user config))
  (-> config
      (update :migrator-username #(or % (:user config)))
      (update :migrator-password #(or % (:password config)))))

(defn populate-db-subsections
  [[db-name] sectionwide settings]
  (if-not db-name settings (merge sectionwide settings)))

(defn fix-up-db-subsection
  [[db-name] sectionwide settings]
  ;; FIXME: double-check this reordering wrt each function,
  ;; i.e. make sure they're OK with going *after* the defaulting.
  (-> settings
      (configure-subsection per-write-database-config-in
                            per-write-database-config-out)
      default-events-ttl
      (prefer-db-user-on-username-mismatch db-name)
      ensure-migrator-info))

(defn configure-read-db
  "Ensures that the config contains a suitable [read-database].  If the
  section already exists, validates and converts it to the internal
  format.  Otherwise, creates it from values in the [database]
  section, which must have already been fully configured."
  [{:keys [database read-database] :as config}]
  (if read-database
    (configure-section config :read-database
                       per-database-config-in
                       per-database-config-out)
    (->> (assoc (sectionwide-settings database) :read-only? true)
         (pls/strip-unknown-keys per-database-config-out)
         (s/validate per-database-config-out)
         (assoc config :read-database))))

(defn configure-db
  [config]
  (-> (coalesce-sections #"^database.*" config)
      ;; Populate first, so that each subsection will have the
      ;; sectionwide values before any fix-ups.  Otherwise operations
      ;; like prefer-db-user-on-username-mismatch may misfire, because
      ;; (in that case) subsections will always end up with a :user
      ;; from the fully expanded sectionwide settings, which will
      ;; supersede a per-section :username, even if the original
      ;; sectionwide setting didn't have a :user.
      (update :database update-section-settings populate-db-subsections)
      (update :database update-section-settings fix-up-db-subsection)
      configure-read-db))

(defn configure-puppetdb
  "Validates the [puppetdb] section of the config"
  [{:keys [puppetdb] :as config :or {puppetdb {}}}]
  (configure-section config :puppetdb puppetdb-config-in puppetdb-config-out))

(defn configure-developer
  [{:keys [developer] :as config :or {developer {}}}]
  (configure-section config :developer developer-config-in developer-config-out))

(def retired-cmd-proc-keys
  [:store-usage :max-frame-size :temp-usage :memory-usage])

(defn warn-command-processing-retirements
  [config]
  (doseq [cmd-proc-key retired-cmd-proc-keys]
    (when (get-in config [:command-processing cmd-proc-key])
      (utils/println-err
       (str (trs "The configuration item `{0}` in the [command-processing] section is retired, please remove this item from your config."
                 (name cmd-proc-key))
            " "
            (trs "Consult the documentation for more details."))))))

(defn configure-command-processing
  [config]
  (warn-command-processing-retirements config)
  (configure-section config :command-processing command-processing-in command-processing-out))

(defn convert-config
  "Given a `config` map (created from the user defined config), validate, default and convert it
   to the internal Clojure format that PuppetDB expects"
  [config]
  (-> config
      configure-db
      configure-command-processing
      configure-puppetdb))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Global Config

(defn validate-vardir
  "Checks that `vardir` is specified, exists, and is writeable, throwing
  appropriate exceptions if any condition is unmet."
  [config]
  (let [vardir (some-> (get-in config [:global :vardir])
                       io/file
                       kitchensink/absolute-path
                       fs/file)]
    (cond
     (nil? vardir)
     (throw (IllegalArgumentException.
             (format "%s %s"
                     (trs "Required setting ''vardir'' is not specified.")
                     (trs "Please set it to a writable directory."))))

     (not (.isAbsolute vardir))
     (throw (IllegalArgumentException.
             (trs "Vardir {0} must be an absolute path." vardir)))

     (not (.exists vardir))
     (throw (java.io.FileNotFoundException.
             (format "%s %s"
                     (trs "Vardir {0} does not exist." vardir)
                     (trs "Please create it and ensure it is writable."))))

     (not (.isDirectory vardir))
     (throw (java.io.FileNotFoundException.
             (trs "Vardir {0} is not a directory." vardir)))

     (not (.canWrite vardir))
     (throw (java.io.FileNotFoundException.
             (trs "Vardir {0} is not writable." vardir)))

     :else
     config)))

(defn normalize-product-name
  "Checks that `product-name` is specified as a legal value, throwing an
  exception if not. Returns `product-name` if it's okay."
  [product-name]
  {:pre [(string? product-name)]
   :post [(= (str/lower-case product-name) %)]}
  (let [lower-product-name (str/lower-case product-name)]
    (when-not (#{"puppetdb" "pe-puppetdb"} lower-product-name)
      (throw (IllegalArgumentException.
              (trs "product-name {0} is illegal; either puppetdb or pe-puppetdb are allowed" product-name))))
    lower-product-name))

(defn configure-globals
  "Configures the global properties from the user defined config"
  [config]
  (update config :global
          #(-> %
               (utils/assoc-when :product-name "puppetdb")
               (update :product-name normalize-product-name)
               (utils/assoc-when :update-server "https://updates.puppetlabs.com/check-for-updates"))))

(defn warn-retirements
  "Warns about configuration retirements.  Abruptly exits the entire
  process if a [global] url-prefix is found."
  [config-data]

  (doseq [[section opt] [[:command-processing :max-frame-size]
                         [:command-processing :memory-usage]
                         [:command-processing :store-usage]
                         [:command-processing :temp-usage]
                         [:database :classname]
                         [:database :conn-keep-alive]
                         [:database :log-slow-statements]
                         [:database :statements-cache-size]
                         [:database :subprotocol]
                         [:read-database :classname]
                         [:read-database :conn-keep-alive]
                         [:read-database :log-slow-statements]
                         [:read-database :statements-cache-size]
                         [:read-database :subprotocol]
                         [:global :catalog-hash-conflict-debugging]]]
    (when (contains? (config-data section) opt)
      (utils/println-err
       (trs "The [{0}] {1} config option has been retired and will be ignored."
            (name section) (name opt)))))

  (when (get-in config-data [:repl])
    (utils/println-err (format "%s %s %s"
                               (trs "The configuration block [repl] is now retired and will be ignored.")
                               (trs "Use [nrepl] instead.")
                               (trs "Consult the documentation for more details."))))

  (when (get-in config-data [:global :url-prefix])
    (utils/println-err (format "%s %s %s"
                               (trs "The configuration item `url-prefix` in the [global] section is retired, please remove this item from your config.")
                               (trs "PuppetDB has a non-configurable context route of `/pdb`.")
                               (trs "Consult the documentation for more details.")))
    (utils/flush-and-exit 1)) ; cf. PDB-2053
  config-data)

(def default-web-router-config
  {:puppetlabs.trapperkeeper.services.metrics.metrics-service/metrics-webservice
   {:route "/metrics"
    :server "default"}
   :puppetlabs.trapperkeeper.services.status.status-service/status-service
   {:route "/status"
    :server "default"}
   :puppetlabs.puppetdb.pdb-routing/pdb-routing-service
   {:route "/pdb"
    :server "default"}
   :puppetlabs.puppetdb.dashboard/dashboard-redirect-service
   {:route "/"
    :server "default"}})

(defn filter-out-non-tk-config [config-data]
  (select-keys config-data
               [:debug :bootstrap-config :config :plugins :help]))

(defn add-web-routing-service-config
  [config-data]
  (let [bootstrap-cfg (->> (tk-bootstrap/find-bootstrap-configs (filter-out-non-tk-config config-data))
                           (mapcat tk-bootstrap/read-config)
                           set)
        ;; If a user didn't specify one of the services in their bootstrap.cfg
        ;; we remove the web-router-config for that service
        filtered-web-router-config (into {} (for [[svc route] default-web-router-config
                                                  :when (contains? bootstrap-cfg (utils/kwd->str svc))]
                                              [svc route]))]
    (doseq [[svc route] filtered-web-router-config]
      (when (get-in config-data [:web-router-service svc])
        (utils/println-err
         (trs "Configuring the route for `{0}` is not allowed. The default route is `{1}` and server is `{2}`."
              svc (:route route) (:server route)))))
    ;; We override the users settings as to make the above routes *not*
    ;; configurable
    (-> config-data
        (assoc-in [:metrics :reporters :jmx :enabled] true)
        (update :web-router-service merge filtered-web-router-config))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(def adjust-and-validate-tk-config
  (comp add-web-routing-service-config
        warn-retirements
        validate-db-settings
        convert-blacklist-config))

(defn hook-tk-parse-config-data
  "This is a robert.hooke compatible hook that is designed to intercept
   trapperkeeper configuration before it is used, so that we may munge &
   customize it.  It may throw {:type ::cli-error :message m}."
  [f args]
  (adjust-and-validate-tk-config (f args)))

(defn process-config!
  "Accepts a map containing all of the user-provided configuration values
  and configures the various PuppetDB subsystems."
  [config]
  (-> config
      configure-globals
      configure-developer
      validate-vardir
      convert-config))

(defn foss? [config]
  (= "puppetdb" (get-in config [:global :product-name])))

(defn pe? [config]
  (= "pe-puppetdb" (get-in config [:global :product-name])))

(defn update-server [config]
  (get-in config [:global :update-server]))

(defn mq-thread-count
  "Returns the desired number of MQ listener threads."
  [config]
  (get-in config [:command-processing :threads]))

(defn reject-large-commands?
  [config]
  (get-in config [:command-processing :reject-large-commands]))

(defn max-command-size
  [config]
  (get-in config [:command-processing :max-command-size]))

(defn stockpile-dir [config]
  (str (io/file (get-in config [:global :vardir]) "stockpile")))

(defprotocol DefaultedConfig
  (get-config [this]))

(defn create-defaulted-config-service [config-transform-fn]
  (tk/service
   DefaultedConfig
   [[:ConfigService get-config]]
   (init [this context]
         (assoc context :config (config-transform-fn (get-config))))
   (get-config [this]
               (:config (service-context this)))))

(def config-service
  (create-defaulted-config-service process-config!))
