(ns lcmf.app-test
  (:require [clojure.test :refer [deftest is testing]]
            [lcmf.app :as app]))

(deftest make-app-state-test
  (let [state (app/make-app-state {:accounts {:items {}}
                                   :booking {:items {}}})]
    (is (= #{:accounts :booking} (set (keys state))))
    (is (= {:items {}} @(get state :accounts)))
    (is (instance? clojure.lang.Atom (get state :booking)))))

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
    (is (= {:items {}} @(:state booking-deps)))
    (is (not (map? (:state (:state booking-deps)))))))

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
                                                     {:module :accounts})}
                                    {:module/id :booking
                                     :module/init! (fn [module-deps]
                                                     (swap! calls conj [:booking module-deps])
                                                     {:module :booking})}])]
    (is (= [:accounts :booking] (mapv first @calls)))
    (is (= {:module :accounts} (get results :accounts)))
    (is (= {:module :booking} (get results :booking)))
    (is (= #{:bus :registry :state} (set (keys (second (first @calls))))))))

(deftest run-startup-checks-test
  (let [results (app/run-startup-checks [{:name :registry
                                          :critical? true
                                          :check (fn [] {:ok? true})}
                                         {:name :http
                                          :critical? false
                                          :check (fn [] {:ok? false :reason :offline})}])]
    (is (= 2 (count results)))
    (is (= true (:ok? (first results))))
    (is (= false (:ok? (second results))))
    (is (= :offline (get-in (second results) [:result :reason])))))

(deftest assert-startup-checks-test
  (is (vector?
       (app/assert-startup-checks! [{:name :registry :critical? true :ok? true}
                                    {:name :http :critical? false :ok? false}])))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Critical startup checks failed"
       (app/assert-startup-checks! [{:name :registry :critical? true :ok? false}]))))

(deftest build-app-test
  (let [state (app/make-app-state {:booking {:items {}}})
        built (app/build-app {:deps {:bus :bus-instance
                                     :registry :registry-instance
                                     :state state}
                              :modules [{:module/id :booking
                                         :module/init! (fn [_]
                                                         {:module :booking
                                                          :ok true})}]
                              :startup-checks [{:name :registry
                                                :critical? true
                                                :check (fn [] {:ok? true})}]})]
    (is (= :bus-instance (:bus built)))
    (is (= {:module :booking :ok true} (get-in built [:modules :booking])))
    (is (= true (get-in built [:startup-checks 0 :ok?])))))

(deftest invalid-module-spec-test
  (let [state (app/make-app-state {:booking {:items {}}})
        deps {:state state}]
    (testing "missing module id"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"missing :module/id"
           (app/init-modules! deps [{:module/init! identity}]))))
    (testing "missing module init"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"missing :module/init!"
           (app/init-modules! deps [{:module/id :booking}]))))))
