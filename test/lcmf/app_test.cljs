(ns lcmf.app-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [lcmf.app :as app]))

(defn- thrown-data [f]
  (try
    (f)
    nil
    (catch :default ex
      (ex-data ex))))

(defn- keyset [m]
  (set (keys m)))

(deftest make-app-state-test
  (let [state (app/make-app-state {:accounts {:items {}}
                                   :booking {:items {}}})]
    (is (= #{:accounts :booking} (set (keys state))))
    (is (satisfies? IAtom (:accounts state)))
    (is (= {:items {}} @(get state :accounts)))
    (is (= {:items {}} @(get state :booking)))))

(deftest module-deps-test
  (let [state (app/make-app-state {:accounts {:items {}}
                                   :booking {:items {}}})
        deps {:bus :bus-instance
              :registry :registry-instance
              :http :http-instance
              :state state}
        booking-deps (app/module-deps deps :booking)]
    (is (= :bus-instance (:bus booking-deps)))
    (is (= :registry-instance (:registry booking-deps)))
    (is (= :http-instance (:http booking-deps)))
    (is (identical? (:booking state) (:state booking-deps)))
    (is (= {:items {}} @(:state booking-deps)))
    (is (nil? (:accounts booking-deps)))))

(deftest module-deps-without-state-slice-test
  (let [state (app/make-app-state {:accounts {:items {}}})
        deps {:bus :bus-instance
              :state state}]
    (is (= {:bus :bus-instance}
           (app/module-deps deps :booking)))))

(deftest module-deps-strict-required-shared-dep-test
  (let [state (app/make-app-state {:booking {:items {}}})
        deps {:state state}
        data (thrown-data #(app/module-deps deps
                                            {:module/id :booking
                                             :module/dep-mode :strict
                                             :module/required-deps #{:bus :state}}))]
    (is (some? data))
    (is (= :missing-required-dependency (:reason data)))
    (is (= :booking (:module-id data)))
    (is (= :bus (:dependency data)))
    (is (= :strict (:dep-mode data)))))

(deftest module-deps-strict-required-state-slice-test
  (let [state (app/make-app-state {:accounts {:items {}}})
        deps {:bus :bus-instance
              :state state}
        data (thrown-data #(app/module-deps deps
                                            {:module/id :booking
                                             :module/dep-mode :strict
                                             :module/required-deps #{:bus :state}}))]
    (is (some? data))
    (is (= :missing-required-dependency (:reason data)))
    (is (= :booking (:module-id data)))
    (is (= :state (:dependency data)))
    (is (= :strict (:dep-mode data)))))

(deftest init-modules-test
  (let [calls (atom [])
        state (app/make-app-state {:accounts {:items {}}
                                   :booking {:items {}}})
        deps {:bus :bus-instance
              :registry :registry-instance
              :state state}
        results (app/init-modules! deps
                                   [{:module/id :accounts
                                     :module/init! (fn [module-deps]
                                                     (swap! calls conj [:accounts module-deps])
                                                     {:module :accounts
                                                      :state-same? (identical? (:state module-deps)
                                                                               (:accounts state))})}
                                    {:module/id :booking
                                     :module/init! (fn [module-deps]
                                                     (swap! calls conj [:booking module-deps])
                                                     {:module :booking
                                                      :state-same? (identical? (:state module-deps)
                                                                               (:booking state))})}])]
    (is (= [:accounts :booking] (mapv first @calls)))
    (is (= #{:bus :registry :state} (set (keys (second (first @calls))))))
    (is (= {:module :accounts
            :state-same? true}
           (get results :accounts)))
    (is (= {:module :booking
            :state-same? true}
           (get results :booking)))))

(deftest run-startup-checks-test
  (let [results (app/run-startup-checks [{:name :registry
                                          :critical? true
                                          :check (fn [] {:ok? true})}
                                         {:name :http
                                          :critical? false
                                          :check (fn [] {:ok? false :reason :offline})}
                                         {:name :logger
                                          :critical? false
                                          :check (fn []
                                                   (throw (ex-info "boom" {:reason :boom})))}
                                         {:name :bad-shape
                                          :critical? false
                                          :check (fn [] {:reason :missing-ok})}])]
    (is (= 4 (count results)))
    (is (= true (:ok? (first results))))
    (is (= false (:ok? (second results))))
    (is (= :offline (get-in (second results) [:result :reason])))
    (is (= false (:ok? (nth results 2))))
    (is (= :check-threw (get-in (nth results 2) [:result :reason])))
    (is (= false (:ok? (nth results 3))))))

(deftest assert-startup-checks-test
  (is (vector?
       (app/assert-startup-checks! [{:name :registry :critical? true :ok? true}
                                    {:name :http :critical? false :ok? false}])))
  (let [data (thrown-data #(app/assert-startup-checks!
                            [{:name :registry :critical? true :ok? false}
                             {:name :http :critical? false :ok? false}]))]
    (is (some? data))
    (is (= :critical-startup-checks-failed (:reason data)))
    (is (= [{:name :registry :critical? true :ok? false}]
           (:failed-checks data)))))

(deftest build-app-test
  (let [state (app/make-app-state {:booking {:items {}}})
        built (app/build-app {:deps {:bus :bus-instance
                                     :registry :registry-instance
                                     :state state}
                              :modules [{:module/id :booking
                                         :module/init! (fn [module-deps]
                                                         {:module :booking
                                                          :ok true
                                                          :same-state? (identical? (:state module-deps)
                                                                                   (:booking state))})}]
                              :startup-checks [{:name :registry
                                                :critical? true
                                                :check (fn [] {:ok? true})}]})]
    (is (= #{:app/status :app/deps :app/modules :app/startup :app/shutdown!}
           (keyset built)))
    (is (= :ready (:app/status built)))
    (is (= :ready (get-in built [:app/startup :status])))
    (is (= :bus-instance (get-in built [:app/deps :bus])))
    (is (= :registry-instance (get-in built [:app/deps :registry])))
    (is (identical? state (get-in built [:app/deps :state])))
    (is (= [:init-modules :run-startup-checks]
           (mapv :phase (get-in built [:app/startup :phases]))))
    (is (= [true true]
           (mapv :ok? (get-in built [:app/startup :phases]))))
    (is (= {:module :booking :ok true :same-state? true}
           (get-in built [:app/modules :booking])))
    (is (= true (get-in built [:app/startup :check-results 0 :ok?])))
    (is (= #{:status :phases :check-results}
           (keyset (:app/startup built))))
    (is (nil? (:modules built)))
    (is (nil? (:startup-checks built)))
    (is (nil? (:shutdown! built)))
    (is (nil? (:bus built)))))

(deftest invalid-module-spec-test
  (let [state (app/make-app-state {:booking {:items {}}})
        deps {:state state}]
    (testing "missing module id"
      (let [data (thrown-data #(app/init-modules! deps [{:module/init! identity}]))]
        (is (some? data))
        (is (= :missing-module-id (:reason data)))))
    (testing "missing module init"
      (let [data (thrown-data #(app/init-modules! deps [{:module/id :booking}]))]
        (is (some? data))
        (is (= :missing-module-init (:reason data)))
        (is (= :booking (:module-id data)))))))

(deftest invalid-startup-check-spec-test
  (testing "missing check name"
    (let [data (thrown-data #(app/run-startup-checks [{:critical? true
                                                       :check (fn [] {:ok? true})}]))]
      (is (some? data))
      (is (= :missing-check-name (:reason data)))))
  (testing "missing check fn"
    (let [data (thrown-data #(app/run-startup-checks [{:name :registry}]))]
      (is (some? data))
      (is (= :missing-check-fn (:reason data))))))

(deftest duplicate-module-id-test
  (let [state (app/make-app-state {:booking {:items {}}})
        deps {:state state}
        data (thrown-data #(app/init-modules! deps
                                              [{:module/id :booking
                                                :module/init! (fn [_] {:module :booking-a})}
                                               {:module/id :booking
                                                :module/init! (fn [_] {:module :booking-b})}]))]
    (is (some? data))
    (is (= :duplicate-module-id (:reason data)))
    (is (= :booking (:module-id data)))
    (is (= #{:reason :module-id :module-spec :initialized-modules}
           (keyset data)))))

(deftest module-init-failure-diagnostics-test
  (let [state (app/make-app-state {:accounts {:items {}}
                                   :booking {:items {}}})
        deps {:state state}
        boom (ex-info "boom" {:reason :boom})
        data (thrown-data #(app/init-modules! deps
                                              [{:module/id :accounts
                                                :module/init! (fn [_]
                                                                {:module :accounts})}
                                               {:module/id :booking
                                                :module/init! (fn [_]
                                                                (throw boom))}]))]
    (is (some? data))
    (is (= :module-init-failed (:reason data)))
    (is (= :init-modules (:phase data)))
    (is (= :booking (:module-id data)))
    (is (= [:accounts] (:initialized-modules data)))
    (is (= :boom (get-in data [:cause-data :reason])))
    (is (= #{:reason :phase :module-id :module-spec :initialized-modules
             :rolled-back-modules :rollback-performed? :cause-data}
           (keyset data)))))

(deftest init-modules-strict-dependencies-test
  (let [state (app/make-app-state {:booking {:items {}}})
        deps {:state state}
        data (thrown-data #(app/init-modules! deps
                                              [{:module/id :booking
                                                :module/dep-mode :strict
                                                :module/required-deps #{:bus :state}
                                                :module/init! (fn [_]
                                                                {:module :booking})}]))]
    (is (some? data))
    (is (= :missing-required-dependency (:reason data)))
    (is (= :booking (:module-id data)))
    (is (= :bus (:dependency data)))
    (is (= :init-modules (:phase data)))
    (is (= #{:reason :module-id :dependency :dep-mode :phase :module-spec
             :initialized-modules}
           (keyset data)))))

(deftest init-modules-rolls-back-on-partial-failure-test
  (let [state (app/make-app-state {:accounts {:items {}}
                                   :booking {:items {}}})
        deps {:state state}
        stopped (atom [])
        boom (ex-info "boom" {:reason :boom})
        data (thrown-data #(app/init-modules! deps
                                              [{:module/id :accounts
                                                :module/init! (fn [_]
                                                                {:module :accounts
                                                                 :stop! (fn []
                                                                          (swap! stopped conj :accounts))})}
                                               {:module/id :booking
                                                :module/init! (fn [_]
                                                                (throw boom))}]))]
    (is (some? data))
    (is (= :module-init-failed (:reason data)))
    (is (= true (:rollback-performed? data)))
    (is (= [:accounts] (:rolled-back-modules data)))
    (is (= [:accounts] @stopped))
    (is (= #{:reason :phase :module-id :module-spec :initialized-modules
             :rolled-back-modules :rollback-performed? :cause-data}
           (keyset data)))))

(deftest stop-modules-test
  (let [stopped (atom [])
        module-results {:accounts {:stop! (fn []
                                            (swap! stopped conj :accounts))}
                        :booking {:stop! (fn []
                                           (swap! stopped conj :booking))}}]
    (is (= module-results
           (app/stop-modules! module-results)))
    (is (= [:booking :accounts] @stopped))))

(deftest build-app-adds-shutdown-test
  (let [state (app/make-app-state {:accounts {:items {}}
                                   :booking {:items {}}})
        stopped (atom [])
        built (app/build-app
               {:deps {:state state}
                :modules [{:module/id :accounts
                           :module/init! (fn [_]
                                           {:module :accounts
                                            :stop! (fn []
                                                     (swap! stopped conj :accounts))})}
                          {:module/id :booking
                           :module/init! (fn [_]
                                           {:module :booking
                                            :stop! (fn []
                                                     (swap! stopped conj :booking))})}]
                :startup-checks []})]
    (is (fn? (:app/shutdown! built)))
    ((:app/shutdown! built))
    (is (= [:booking :accounts] @stopped))))

(deftest build-app-startup-report-with-noncritical-check-failure-test
  (let [state (app/make-app-state {:booking {:items {}}})
        built (app/build-app
               {:deps {:state state}
                :modules [{:module/id :booking
                           :module/init! (fn [_]
                                           {:module :booking})}]
                :startup-checks [{:name :http
                                  :critical? false
                                  :check (fn []
                                           {:ok? false :reason :offline})}]})]
    (is (= :ready (:app/status built)))
    (is (= :ready (get-in built [:app/startup :status])))
    (is (= {:phase :run-startup-checks
            :ok? true
            :critical-failures? false}
           (select-keys (last (get-in built [:app/startup :phases]))
                        [:phase :ok? :critical-failures?])))))

(deftest build-app-startup-report-on-critical-check-failure-test
  (let [state (app/make-app-state {:booking {:items {}}})
        data (thrown-data #(app/build-app
                            {:deps {:state state}
                             :modules [{:module/id :booking
                                        :module/init! (fn [_]
                                                        {:module :booking})}]
                             :startup-checks [{:name :registry
                                               :critical? true
                                               :check (fn []
                                                        {:ok? false
                                                         :reason :missing-provider})}]}))]
    (is (some? data))
    (is (= :critical-startup-checks-failed (:reason data)))
    (is (= :startup-failed (get-in data [:startup :status])))
    (is (= [:init-modules :run-startup-checks]
           (mapv :phase (get-in data [:startup :phases]))))
    (is (= [true false]
           (mapv :ok? (get-in data [:startup :phases]))))
    (is (= #{:reason :failed-checks :startup}
           (keyset data)))))
