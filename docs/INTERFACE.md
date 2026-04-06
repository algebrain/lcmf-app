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
```

Идея:

- shared app deps остаются общими;
- из `:state` модуль получает только свой участок.

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

- финальная app map с исходными deps, `:modules` и `:startup-checks`

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
