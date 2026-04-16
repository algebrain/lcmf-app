(ns lcmf.app)

(defn- module-spec->shape
  [module-or-id]
  (if (map? module-or-id)
    {:module-id (:module/id module-or-id)
     :required-deps (:module/required-deps module-or-id)
     :dep-mode (or (:module/dep-mode module-or-id) :lenient)}
    {:module-id module-or-id
     :required-deps nil
     :dep-mode :lenient}))

(defn- has-module-dependency?
  [app-deps module-id dependency]
  (if (= :state dependency)
    (contains? (:state app-deps) module-id)
    (contains? app-deps dependency)))

(defn- missing-dependency-ex
  [{:keys [module-id dep-mode]} dependency]
  (ex-info "Missing required module dependency"
           {:reason :missing-required-dependency
            :module-id module-id
            :dependency dependency
            :dep-mode dep-mode}))

(defn- stop-module-results!
  [module-results]
  (doseq [[_ result] (reverse (vec module-results))]
    (when-let [stop! (:stop! result)]
      (stop!))))

(defn make-app-state
  "Builds app state from a map of module-id -> initial-state.
   Each module gets its own atom."
  [module->initial-state]
  (into {}
        (map (fn [[module-id initial-state]]
               [module-id (atom initial-state)]))
        module->initial-state))

(defn module-deps
  "Builds the dependency map for a single module.
   The module receives shared app deps plus only its own state/runtime slice.

   `module-or-id` may be either a module id keyword or a module spec map.
   Strict dependency validation is enabled only for module specs with:

   {:module/dep-mode :strict
    :module/required-deps #{...}}"
  [app-deps module-or-id]
  (let [{:keys [module-id required-deps dep-mode] :as module-shape}
        (module-spec->shape module-or-id)]
    (when (and (= :strict dep-mode) (seq required-deps))
      (doseq [dependency required-deps]
        (when-not (has-module-dependency? app-deps module-id dependency)
          (throw (missing-dependency-ex module-shape dependency)))))
    (cond-> (dissoc app-deps :state)
      (contains? (:state app-deps) module-id)
      (assoc :state (get (:state app-deps) module-id)))))

(defn init-modules!
  "Initializes modules in order.

   Each module spec is a map:
   {:module/id :booking
    :module/init! booking/init!}

   Returns a map of module-id -> init result."
  [app-deps module-specs]
  (loop [acc {}
         remaining module-specs]
    (if-let [module-spec (first remaining)]
      (let [id (:module/id module-spec)
            init! (:module/init! module-spec)]
        (when-not id
          (throw (ex-info "Module spec is missing :module/id"
                          {:reason :missing-module-id
                           :module-spec module-spec})))
        (when-not init!
          (throw (ex-info "Module spec is missing :module/init!"
                          {:reason :missing-module-init
                           :module-id id
                           :module-spec module-spec})))
        (when (contains? acc id)
          (throw (ex-info "Duplicate module id"
                          {:reason :duplicate-module-id
                           :module-id id
                           :module-spec module-spec
                           :initialized-modules (vec (keys acc))})))
        (let [deps (try
                     (module-deps app-deps module-spec)
                     (catch #?(:clj Throwable :cljs :default) ex
                       (throw (ex-info "Module dependency resolution failed"
                                       (merge {:phase :init-modules
                                               :module-id id
                                               :module-spec module-spec
                                               :initialized-modules (vec (keys acc))}
                                              (ex-data ex))
                                       ex))))
              init-result (try
                            {:ok? true
                             :result (init! deps)}
                            (catch #?(:clj Throwable :cljs :default) ex
                              {:ok? false
                               :exception ex}))]
          (if (:ok? init-result)
            (recur (assoc acc id
                          (:result init-result))
                   (next remaining))
            (do
              (stop-module-results! acc)
              (throw (ex-info "Module initialization failed"
                              {:reason :module-init-failed
                               :phase :init-modules
                               :module-id id
                               :module-spec module-spec
                               :initialized-modules (vec (keys acc))
                               :rolled-back-modules (vec (reverse (keys acc)))
                               :rollback-performed? true
                               :cause-data (ex-data (:exception init-result))}
                              (:exception init-result)))))))
      acc)))

(defn stop-modules!
  "Stops module lifecycle hooks in reverse initialization order.

   Each module result may optionally provide:
   {:stop! (fn [] ...)}"
  [module-results]
  (stop-module-results! module-results)
  module-results)

(defn- startup-phase
  [phase data]
  (merge {:phase phase}
         data))

(defn- startup-report
  [status phases check-results]
  {:status status
   :phases (vec phases)
   :check-results (vec check-results)})

(defn run-startup-checks
  "Runs startup checks and returns structured results.

   Each check is a map:
   {:name :registry
    :critical? true
    :check (fn [] {:ok? true})}"
  [checks]
  (mapv (fn [{:keys [name critical? check] :as check-spec}]
          (when-not name
            (throw (ex-info "Startup check is missing :name"
                            {:reason :missing-check-name
                             :check-spec check-spec})))
          (when-not check
            (throw (ex-info "Startup check is missing :check"
                            {:reason :missing-check-fn
                             :check-spec check-spec})))
          (try
            (let [result (check)]
              {:name name
               :critical? (boolean critical?)
               :ok? (true? (:ok? result))
               :result result})
            (catch #?(:clj Throwable :cljs :default) ex
              {:name name
               :critical? (boolean critical?)
               :ok? false
               :result {:ok? false
                        :reason :check-threw
                        :exception ex}})))
        checks))

(defn assert-startup-checks!
  "Throws if any critical startup check failed."
  [check-results]
  (let [failed-critical (filter (fn [{:keys [critical? ok?]}]
                                  (and critical? (not ok?)))
                                check-results)]
    (when (seq failed-critical)
      (throw (ex-info "Critical startup checks failed"
                      {:reason :critical-startup-checks-failed
                       :failed-checks (vec failed-critical)})))
    check-results))

(defn build-app
  "Builds an app from shared deps, module specs, and optional startup checks.

   opts:
   {:deps {...}
    :modules [...]
    :startup-checks [...]}"
  [{:keys [deps modules startup-checks]}]
  (let [module-results (init-modules! deps modules)
        init-phase (startup-phase :init-modules
                                  {:ok? true
                                   :module-count (count module-results)})
        check-results (run-startup-checks startup-checks)
        critical-failures? (boolean
                            (seq (filter (fn [{:keys [critical? ok?]}]
                                           (and critical? (not ok?)))
                                         check-results)))
        checks-phase (startup-phase :run-startup-checks
                                    {:ok? (not critical-failures?)
                                     :critical-failures? critical-failures?
                                     :check-count (count check-results)})
        startup (startup-report (if critical-failures?
                                  :startup-failed
                                  :ready)
                                [init-phase checks-phase]
                                check-results)]
    (when critical-failures?
      (try
        (assert-startup-checks! check-results)
        (catch #?(:clj Throwable :cljs :default) ex
          (throw (ex-info "Critical startup checks failed"
                          (merge (ex-data ex)
                                 {:startup startup})
                          ex)))))
    {:app/status :ready
     :app/deps deps
     :app/modules module-results
     :app/startup startup
     :app/shutdown! (fn []
                      (stop-modules! module-results))}))
