(ns lcmf.app)

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
   The module receives shared app deps plus only its own state/runtime slice."
  [app-deps module-id]
  (cond-> (dissoc app-deps :state)
    (contains? (:state app-deps) module-id)
    (assoc :state (get (:state app-deps) module-id))))

(defn init-modules!
  "Initializes modules in order.

   Each module spec is a map:
   {:module/id :booking
    :module/init! booking/init!}

   Returns a map of module-id -> init result."
  [app-deps module-specs]
  (reduce (fn [acc {:keys [module/id module/init!]
                    :as module-spec}]
            (when-not id
              (throw (ex-info "Module spec is missing :module/id"
                              {:reason :missing-module-id
                               :module-spec module-spec})))
            (when-not init!
              (throw (ex-info "Module spec is missing :module/init!"
                              {:reason :missing-module-init
                               :module-id id
                               :module-spec module-spec})))
            (assoc acc id (init! (module-deps app-deps id))))
          {}
          module-specs))

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
        check-results (run-startup-checks startup-checks)]
    (assert-startup-checks! check-results)
    (assoc deps
           :modules module-results
           :startup-checks check-results)))
