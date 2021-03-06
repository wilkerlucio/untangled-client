(ns untangled.client.core-spec
  (:require
    [om.next :as om :refer-macros [defui]]
    [untangled.client.core :as uc]
    [untangled-spec.core :refer-macros
     [specification behavior assertions provided component when-mocking]]
    [om.next.protocols :as omp]
    [cljs.core.async :as async]
    [untangled.client.logging :as log]
    [untangled.dom :as udom]))

(defui Child
  static om/Ident
  (ident [this props] [:child/by-id (:id props)])
  static om/IQuery
  (query [this] [:id :label]))

(defui Parent
  static uc/InitialAppState
  (uc/initial-state [this params] {:ui/checked true})
  static om/Ident
  (ident [this props] [:parent/by-id (:id props)])
  static om/IQuery
  (query [this] [:ui/checked :id :title {:child (om/get-query Child)}]))

(specification "merge-state!"
  (assertions
    "merge-query is the component query joined on it's ident"
    (#'uc/component-merge-query Parent {:id 42}) => [{[:parent/by-id 42] [:ui/checked :id :title {:child (om/get-query Child)}]}])
  (component "preprocessing the object to merge"
    (let [no-state (atom {:parent/by-id {}})
          no-state-merge-data (:merge-data (#'uc/preprocess-merge no-state Parent {:id 42}))
          state-with-old (atom {:parent/by-id {42 {:ui/checked true :id 42 :title "Hello"}}})
          id [:parent/by-id 42]
          old-state-merge-data (-> (#'uc/preprocess-merge state-with-old Parent {:id 42}) :merge-data :untangled/merge)]
      (assertions
        "Uses the existing object in app state as base for merge when present"
        (get-in old-state-merge-data [id :ui/checked]) => true
        "Marks fields that were queried but are not present as plumbing/not-found"
        old-state-merge-data => {[:parent/by-id 42] {:id         42
                                                     :ui/checked true
                                                     :title      :untangled.client.impl.om-plumbing/not-found
                                                     :child      :untangled.client.impl.om-plumbing/not-found}})))
  (let [state (atom {})
        data {}]
    (when-mocking
      (uc/preprocess-merge s c d) => {:merge-data :the-data :merge-query :the-query}
      (uc/integrate-ident! s i op args op args) => :ignore
      (uc/get-class-ident c p) => [:table :id]
      (om/merge! r d q) => :ignore
      (om/app-state r) => state
      (omp/queue! r kw) => (assertions
                             "schedules re-rendering of all affected paths"
                             kw => [:children :items])

      (uc/merge-state! :reconciler :component data :append [:children] :replace [:items 0]))))

(specification "integrate-ident!"
  (let [state (atom {:a    {:path [[:table 2]]}
                     :b    {:path [[:table 2]]}
                     :d    [:table 6]
                     :many {:path [[:table 99] [:table 88] [:table 77]]}
                     })]
    (behavior "Can append to an existing vector"
      (uc/integrate-ident! state [:table 3] :append [:a :path])
      (assertions
        (get-in @state [:a :path]) => [[:table 2] [:table 3]])
      (uc/integrate-ident! state [:table 3] :append [:a :path])
      (assertions
        "(is a no-op if the ident is already there)"
        (get-in @state [:a :path]) => [[:table 2] [:table 3]]))
    (behavior "Can prepend to an existing vector"
      (uc/integrate-ident! state [:table 3] :prepend [:b :path])
      (assertions
        (get-in @state [:b :path]) => [[:table 3] [:table 2]])
      (uc/integrate-ident! state [:table 3] :prepend [:b :path])
      (assertions
        "(is a no-op if already there)"
        (get-in @state [:b :path]) => [[:table 3] [:table 2]]))
    (behavior "Can create/replace a to-one ident"
      (uc/integrate-ident! state [:table 3] :replace [:c :path])
      (uc/integrate-ident! state [:table 3] :replace [:d])
      (assertions
        (get-in @state [:d]) => [:table 3]
        (get-in @state [:c :path]) => [:table 3]
        ))
    (behavior "Can replace an existing to-many element in a vector"
      (uc/integrate-ident! state [:table 3] :replace [:many :path 1])
      (assertions
        (get-in @state [:many :path]) => [[:table 99] [:table 3] [:table 77]]))))


(specification "Untangled Application -- clear-pending-remote-requests!"
  (let [channel (async/chan 1000)
        mock-app (uc/map->Application {:queue channel})]
    (async/put! channel 1 #(async/put! channel 2 (fn [] (async/put! channel 3 (fn [] (async/put! channel 4))))))

    (uc/clear-pending-remote-requests! mock-app)

    (assertions
      "Removes any pending items in the network queue channel"
      (async/poll! channel) => nil)))

(defui BadResetAppRoot
  Object
  (render [this] nil))

(defui ResetAppRoot
  static uc/InitialAppState
  (initial-state [this params] {:x 1}))

(specification "Untangled Application -- reset-app!"
  (let [scb-calls (atom 0)
        custom-calls (atom 0)
        mock-app (uc/map->Application {:started-callback (fn [] (swap! scb-calls inc))})
        cleared-network? (atom false)
        merged-unions? (atom false)
        history-reset? (atom false)
        re-rendered? (atom false)
        state (atom {})]
    (behavior "Logs an error if the supplied component does not implement InitialAppState"
      (when-mocking
        (log/error e) => (assertions
                           e => "The specified root component does not implement InitialAppState!")
        (uc/reset-app! mock-app BadResetAppRoot nil)))

    (behavior "On a proper app root"
      (when-mocking
        (uc/clear-queue t) => (reset! cleared-network? true)
        (om/app-state r) => state
        (uc/merge-alternate-union-elements! app r) => (reset! merged-unions? true)
        (uc/reset-history-impl a) => (reset! history-reset? true)
        (udom/force-render a) => (reset! re-rendered? true)

        (uc/reset-app! mock-app ResetAppRoot nil)
        (uc/reset-app! mock-app ResetAppRoot :original)
        (uc/reset-app! mock-app ResetAppRoot (fn [a] (swap! custom-calls inc)))
        )

      (assertions
        "Clears the network queue"
        @cleared-network? => true
        "Resets Om's app history"
        @history-reset? => true
        "Sets the base state from component"
        @state => {:x 1 :om.next/tables #{}}
        "Attempts to merge alternate union branches into state"
        @merged-unions? => true
        "Re-renders the app"
        @re-rendered? => true
        "Calls the original started-callback when callback is :original"
        @scb-calls => 1
        "Calls the supplied started-callback when callback is a function"
        @custom-calls => 1))))

(specification "Mounting an Untangled Application"
  (let [mounted-mock-app {:mounted? true :initial-state {}}]
    (provided "When it is already mounted"
      (uc/refresh* a) =1x=> (do
                              (assertions
                                "Refreshes the UI"
                                1 => 1)
                              a)

      (uc/mount* mounted-mock-app :fake-root :dom-id)))
  (behavior "When is is not already mounted"
    (let [mock-app {:mounted? false :initial-state {:a 1} :reconciler-options :OPTIONS}]
      (when-mocking
        (uc/initialize app state root dom opts) => (do
                                                     (assertions
                                                       "Initializes the app with a plain map when root does not implement InitialAppState"
                                                       state => {:a 1}
                                                       ))

        (uc/mount* mock-app :fake-root :dom-id)))
    (let [supplied-atom (atom {:a 1})
          mock-app {:mounted? false :initial-state supplied-atom :reconciler-options :OPTIONS}]
      (when-mocking
        (uc/initialize app state root dom opts) => (do
                                                     (assertions
                                                       "Initializes the app with a supplied atom when root does not implement InitialAppState"
                                                       {:a 1} => @state))

        (uc/mount* mock-app :fake-root :dom-id)))
    (let [mock-app {:mounted? false :initial-state {:a 1} :reconciler-options :OPTIONS}]
      (when-mocking
        (log/warn msg) =1x=> (do (assertions "warns about duplicate initialization"
                                   msg =fn=> (partial re-matches #"^You supplied.*")))
        (uc/initialize app state root dom opts) => (do
                                                     (assertions
                                                       "Initializes the app with the InitialAppState"
                                                       state => (uc/initial-state Parent nil)))

        (uc/mount* mock-app Parent :dom-id)))
    (let [mock-app {:mounted? false :initial-state (atom {:a 1}) :reconciler-options :OPTIONS}]
      (behavior "When both atom and InitialAppState are present:"
        (when-mocking
          (log/warn msg) =1x=> true
          (om/tree->db c d merge-idents) => (do
                                              (behavior "Normalizes InitialAppState:"
                                                (assertions
                                                  "includes Om tables"
                                                  merge-idents => true
                                                  "uses the Root UI component query"
                                                  c => Parent
                                                  "uses InitialAppState as the data"
                                                  d => (uc/initial-state Parent nil)))
                                              :NORMALIZED-STATE)
          (uc/initialize app state root dom opts) => (do
                                                       (assertions
                                                         "Overwrites the supplied atom with the normalized InitialAppState"
                                                         @state => :NORMALIZED-STATE))

          (uc/mount* mock-app Parent :dom-id))))))
