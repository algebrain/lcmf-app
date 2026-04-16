# Интерфейс `lcmf-app`

Этот документ описывает публичный интерфейс библиотеки
[`lcmf-app`](https://github.com/algebrain/lcmf-app).

Библиотека реализует тонкий app-level wiring слой для
[`LCMF`](https://github.com/algebrain/lcmf-docs).

## Назначение

`lcmf-app` помогает:

- собирать карту модульного состояния;
- передавать модулю только его зависимости;
- инициализировать модули в app-level порядке;
- выполнять стартовые проверки;
- возвращать финальную app map.

Библиотека не:

- владеет предметной логикой модулей;
- заменяет `bus` или `registry`;
- становится framework поверх приложения.

## `make-app-state`

Создает app-level карту модульного состояния.

Общая форма:

```clojure
(make-app-state module->initial-state)
```

Где `module->initial-state`:

```clojure
{:accounts accounts.state/initial-state
 :booking booking.state/initial-state}
```

Возвращаемое значение:

- карта `module-id -> atom`

Пример:

```clojure
(ns my.app
  (:require [lcmf.app :as app]))

(def app-state
  (app/make-app-state
   {:accounts {:items {}}
    :booking {:items {}}}))
```

## `module-deps`

Строит dependency map для одного модуля.

Общая форма:

```clojure
(module-deps app-deps module-id)
(module-deps app-deps module-spec)
```

Идея:

- shared app deps остаются общими;
- из `:state` модуль получает только свой участок.
- при вызове по `module-id` функция остается lenient и сохраняет обратную
  совместимость;
- strict dependency validation включается только через `module-spec`.

Минимальный `module-spec` для strict режима:

```clojure
{:module/id :booking
 :module/dep-mode :strict
 :module/required-deps #{:bus :registry :state}}
```

Fail-fast в strict режиме:

- если не хватает shared dependency вроде `:bus`, бросается `ExceptionInfo`
  с `{:reason :missing-required-dependency ...}`;
- если у модуля отсутствует required `:state` slice, это тоже считается
  `:missing-required-dependency`;
- `init-modules!` пробрасывает такую ошибку наружу с `:phase :init-modules`.

Пример:

```clojure
(def booking-deps
  (app/module-deps
   {:bus event-bus
    :registry read-registry
    :http http-runtime
    :state app-state}
   :booking))
```

Пример strict dependency validation:

```clojure
(app/module-deps
 {:bus event-bus
  :state app-state}
 {:module/id :booking
  :module/dep-mode :strict
  :module/required-deps #{:bus :state}})
```

Пример strict failure:

```clojure
(app/module-deps
 {:state app-state}
 {:module/id :booking
  :module/dep-mode :strict
  :module/required-deps #{:bus :state}})
;; throws ex-info with
;; {:reason :missing-required-dependency
;;  :module-id :booking
;;  :dependency :bus
;;  :dep-mode :strict}
```

## `init-modules!`

Инициализирует модули в заданном порядке.

Общая форма:

```clojure
(init-modules! app-deps module-specs)
```

Где каждый `module-spec` имеет вид:

```clojure
{:module/id :booking
 :module/init! booking/init!}
```

Fail-fast инварианты:

- `:module/id` обязателен;
- `:module/init!` обязателен;
- duplicate `:module/id` запрещен;
- при partial init failure уже инициализированные модули откатываются через
  их `:stop!`, если такой hook был возвращен;
- если `init!` падает, библиотека бросает `ExceptionInfo` с app-level
  контекстом:
  `:reason`, `:phase`, `:module-id`, `:initialized-modules`,
  `:module-spec`, `:cause-data`;
- при rollback exception data дополнительно включает:
  `:rollback-performed?` и `:rolled-back-modules`.

Возвращаемое значение:

- карта `module-id -> init-result`

Пример:

```clojure
(app/init-modules!
 {:bus event-bus
  :registry read-registry
  :http http-runtime
  :state app-state}
 [{:module/id :accounts
   :module/init! accounts/init!}
  {:module/id :booking
   :module/init! booking/init!}])
```

Пример fail-fast на duplicate module id:

```clojure
(app/init-modules!
 {:state app-state}
 [{:module/id :booking
   :module/init! booking/init!}
  {:module/id :booking
   :module/init! booking-v2/init!}])
;; throws ex-info with
;; {:reason :duplicate-module-id
;;  :module-id :booking}
```

Пример enriched diagnostics при падении `init!`:

```clojure
(try
  (app/init-modules!
   {:state app-state}
   [{:module/id :accounts
     :module/init! accounts/init!}
    {:module/id :booking
     :module/init! (fn [_]
                     (throw (ex-info "boom" {:reason :boom})))}])
  (catch cljs.core.ExceptionInfo ex
    (ex-data ex)))
;; => {:reason :module-init-failed
;;     :phase :init-modules
;;     :module-id :booking
;;     :initialized-modules [:accounts]
;;     :cause-data {:reason :boom}}
```

Пример rollback при partial init failure:

```clojure
(app/init-modules!
 {:state app-state}
 [{:module/id :accounts
   :module/init! (fn [_]
                   {:stop! (fn [] (js/console.log "accounts stopped"))})}
  {:module/id :booking
   :module/init! (fn [_]
                   (throw (ex-info "boom" {:reason :boom})))}])
;; throws ex-info with
;; {:reason :module-init-failed
;;  :rollback-performed? true
;;  :rolled-back-modules [:accounts]}
```

## `stop-modules!`

Останавливает module lifecycle hooks в обратном порядке инициализации.

Общая форма:

```clojure
(stop-modules! module-results)
```

Ожидаемая форма module result:

```clojure
{:module :booking
 :stop! (fn [] ...)}
```

Возвращаемое значение:

- исходная карта `module-results`

Пример:

```clojure
(app/stop-modules!
 {:accounts {:stop! (fn [] ...)}
  :booking {:stop! (fn [] ...)}})
```

## `run-startup-checks`

Запускает стартовые проверки и возвращает их результаты.

Общая форма:

```clojure
(run-startup-checks checks)
```

Где каждая проверка имеет вид:

```clojure
{:name :registry
 :critical? true
 :check (fn [] {:ok? true})}
```

Возвращаемое значение:

- вектор результатов с `:name`, `:critical?`, `:ok?`, `:result`

Пример:

```clojure
(app/run-startup-checks
 [{:name :registry
   :critical? true
   :check (fn [] {:ok? true})}
  {:name :http
   :critical? false
   :check (fn [] {:ok? false :reason :offline})}])
```

## `assert-startup-checks!`

Проверяет результаты стартовых проверок и бросает исключение, если упал хотя бы
один critical check.

Общая форма:

```clojure
(assert-startup-checks! check-results)
```

Пример:

```clojure
(-> checks
    app/run-startup-checks
    app/assert-startup-checks!)
```

## `build-app`

Собирает приложение из deps, модулей и startup checks.

Общая форма:

```clojure
(build-app {:deps {...}
            :modules [...]
            :startup-checks [...]})
```

Возвращаемое значение:

- финальная canonical app map:
  `:app/status`, `:app/deps`, `:app/modules`, `:app/startup`,
  `:app/shutdown!`

`build-app` делает startup pipeline более явным.
На текущем этапе он фиксирует две фазы:

- `:init-modules`
- `:run-startup-checks`

Форма startup report:

```clojure
{:status :ready
 :phases [{:phase :init-modules
           :ok? true
           :module-count 2}
          {:phase :run-startup-checks
           :ok? true
           :critical-failures? false
           :check-count 1}]
 :check-results [...]}
```

Форма canonical app map:

```clojure
{:app/status :ready
 :app/deps {:bus event-bus
            :registry read-registry
            :http http-runtime
            :state app-state}
 :app/modules {:accounts {...}
               :booking {...}}
 :app/startup {...}
 :app/shutdown! (fn [] ...)}
```

Если падает critical startup check, исключение содержит `:startup` с тем же
structured report, но со статусом `:startup-failed`.

Пример:

```clojure
(ns my.app
  (:require [lcmf.app :as app]
            [lcmf.bus :as bus]
            [accounts.core :as accounts]
            [booking.core :as booking]))

(defn init-app! []
  (let [event-bus (bus/make-bus)
        app-state (app/make-app-state
                   {:accounts {:items {}}
                    :booking {:items {}}})]
    (app/build-app
     {:deps {:bus event-bus
             :registry read-registry
             :http http-runtime
             :state app-state}
     :modules [{:module/id :accounts
                 :module/init! accounts/init!}
                {:module/id :booking
                 :module/init! booking/init!}]
      :startup-checks [{:name :registry
                        :critical? true
                        :check (fn []
                                 {:ok? true})}]})))
```

Пример shutdown:

```clojure
(let [app (app/build-app {:deps deps
                          :modules modules
                          :startup-checks []})]
  ((:app/shutdown! app)))
```

Пример чтения startup report:

```clojure
(let [app (app/build-app {:deps deps
                          :modules modules
                          :startup-checks checks})]
  (:app/status app)
  (:app/startup app))
```

Пример critical startup failure:

```clojure
(try
  (app/build-app
   {:deps deps
    :modules modules
    :startup-checks [{:name :registry
                      :critical? true
                      :check (fn []
                               {:ok? false
                                :reason :missing-provider})}]})
  (catch cljs.core.ExceptionInfo ex
    (ex-data ex)))
;; => {:reason :critical-startup-checks-failed
;;     :startup {:status :startup-failed
;;               :phases [...]} }
```

## Минимальный walkthrough

```clojure
(ns my.app
  (:require [lcmf.app :as app]
            [lcmf.bus :as bus]
            [accounts.core :as accounts]
            [booking.core :as booking]))

(defn init-app! []
  (let [event-bus (bus/make-bus)
        app-state (app/make-app-state
                   {:accounts {:items {}}
                    :booking {:items {}}})]
    (app/build-app
     {:deps {:bus event-bus
             :registry read-registry
             :http http-runtime
             :state app-state}
      :modules [{:module/id :accounts
                 :module/init! accounts/init!}
                {:module/id :booking
                 :module/init! booking/init!}]
      :startup-checks [{:name :registry
                        :critical? true
                        :check (fn []
                                 {:ok? true})}]})))
```

## Стабильность контракта

На текущем этапе публичным контрактом `lcmf-app` считаются:

- canonical app map из `build-app`:
  `:app/status`, `:app/deps`, `:app/modules`, `:app/startup`,
  `:app/shutdown!`
- shape startup report:
  `{:status ... :phases [...] :check-results [...]}`
- fail-fast reason keys для app assembly:
  `:missing-module-id`
  `:missing-module-init`
  `:duplicate-module-id`
  `:missing-required-dependency`
  `:module-init-failed`
  `:critical-startup-checks-failed`
- lifecycle surface:
  `stop-modules!` и `:app/shutdown!`

Ломающими изменениями считаются:

- удаление или переименование любого ключа из canonical app map;
- удаление или переименование ключей `:status`, `:phases`, `:check-results`
  в startup report;
- удаление или переименование перечисленных `:reason`;
- удаление `stop-modules!` или `:app/shutdown!`;
- несовместимое изменение shape exception data для уже зафиксированных
  startup failure сценариев.

Неломающими изменениями обычно считаются:

- добавление новых полей в app map или startup report;
- добавление новых фаз startup pipeline;
- добавление новых `:reason`, если существующие не ломаются;
- расширение diagnostics дополнительными полями без удаления уже
  зафиксированных.
