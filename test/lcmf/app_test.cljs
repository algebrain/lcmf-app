(ns lcmf.app-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [lcmf.app :as app]))

(defn- thrown-data [f]
  (try
    (f)
    nil
    (catch :default ex
      (ex-data ex))))

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
    (is (= :bus-instance (:bus built)))
    (is (= :registry-instance (:registry built)))
    (is (identical? state (:state built)))
    (is (= {:module :booking :ok true :same-state? true}
           (get-in built [:modules :booking])))
    (is (= true (get-in built [:startup-checks 0 :ok?])))))

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
