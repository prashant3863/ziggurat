(ns ziggurat.messaging.producer-test
  (:require [clojure.test :refer :all]
            [langohr.channel :as lch]
            [langohr.exchange :as le]
            [langohr.queue :as lq]
            [ziggurat.config :refer [rabbitmq-config]]
            [ziggurat.fixtures :as fix]
            [ziggurat.messaging.connection :refer [connection]]
            [ziggurat.messaging.producer :as producer]
            [ziggurat.messaging.util :as util]
            [ziggurat.util.rabbitmq :as rmq]
            [langohr.basic :as lb]
            [ziggurat.config :as config]))

(use-fixtures :once fix/init-rabbit-mq)

(deftest retry-test
  (testing "message with a retry count of greater than 0 will publish to delay queue"
    (fix/with-queues
      {:default {:handler-fn #(constantly nil)}}
      (let [message          {:foo "bar" :retry-count 5}
            expected-message {:foo "bar" :retry-count 4}
            topic-entity     :default]
        (producer/retry message topic-entity)
        (let [message-from-mq (rmq/get-msg-from-delay-queue "default")]
          (is (= expected-message message-from-mq))))))

  (testing "message with a retry count of 0 will publish to dead queue"
    (fix/with-queues
      {:default {:handler-fn #(constantly nil)}}
      (let [message          {:foo "bar" :retry-count 0}
            expected-message (dissoc message :retry-count)
            topic-entity     :default]
        (producer/retry message topic-entity)
        (let [message-from-mq (rmq/get-msg-from-dead-queue "default")]
          (is (= expected-message message-from-mq))))))

  (testing "message with no retry count will publish to delay queue"
    (fix/with-queues
      {:default {:handler-fn #(constantly nil)}}
      (let [message          {:foo "bar"}
            expected-message {:foo "bar" :retry-count 5}
            topic-entity     :default]
        (producer/retry message topic-entity)
        (let [message-from-mq (rmq/get-msg-from-delay-queue "default")]
          (is (= message-from-mq expected-message))))))

  (testing "publish to delay queue publishes with expiration from config"
    (fix/with-queues
      {:default {:handler-fn #(constantly nil)}}
      (let [message        {:foo "bar"}
            topic-entity   :default
            expected-props {:content-type "application/octet-stream"
                            :persistent   true
                            :expiration   (str (get-in (rabbitmq-config) [:delay :queue-timeout-ms]))}]
        (with-redefs [lb/publish (fn [_ _ _ _ props]
                                   (is (= expected-props props)))]
          (producer/publish-to-delay-queue topic-entity message))))))

(deftest make-queues-test
  (let [ziggurat-config (config/ziggurat-config)]
    (testing "When retries are enabled"
      (with-redefs [config/ziggurat-config (constantly (assoc ziggurat-config
                                                              :retry {:enabled true}))]
        (testing "it does not create queues when stream-routes are not passed"
          (let [counter (atom 0)]
            (with-redefs [producer/create-and-bind-queue (fn
                                                           ([_ _] (swap! counter inc))
                                                           ([_ _ _] (swap! counter inc)))]
              (producer/make-queues nil)
              (producer/make-queues [])
              (is (= 0 @counter)))))

        (testing "it calls create-and-bind-queue for each queue creation and each stream-route when stream-routes are passed"
          (let [counter       (atom 0)
                stream-routes {:test  {:handler-fn #(constantly nil)}
                               :test2 {:handler-fn #(constantly nil)}}]
            (with-redefs [producer/create-and-bind-queue (fn
                                                           ([_ _] (swap! counter inc))
                                                           ([_ _ _] (swap! counter inc)))]
              (producer/make-queues stream-routes)
              (is (= (* (count stream-routes) 3) @counter)))))

        (testing "it calls create queue for each queue creation with appropriate props"
          (let [counter               (atom 0)
                stream-routes         {:default {:handler-fn #(constantly nil)}}
                instant-queue-name    (util/prefixed-queue-name "default" (:queue-name (:instant (rabbitmq-config))))
                instant-exchange-name (util/prefixed-queue-name "default" (:exchange-name (:instant (rabbitmq-config))))
                delay-queue-name      (util/prefixed-queue-name "default" (:queue-name (:delay (rabbitmq-config))))
                dead-queue-name       (util/prefixed-queue-name "default" (:queue-name (:dead-letter (rabbitmq-config))))]
            (with-redefs [producer/create-queue           (fn [queue props _]
                                                            (swap! counter inc)
                                                            (cond
                                                              (= queue instant-queue-name) (is (empty? (:arguments props)))
                                                              (= queue delay-queue-name) (is (= {"x-dead-letter-exchange" instant-exchange-name} props))
                                                              (= queue dead-queue-name) (is (empty? (:arguments props)))))
                          producer/declare-exchange       (fn [_ _] true)
                          producer/bind-queue-to-exchange (fn [_ _ _] true)]
              (producer/make-queues stream-routes)
              (is (= (* (count stream-routes) 3) @counter)))))

        (testing "it creates queues with topic entity from stream routes"
          (with-open [ch (lch/open connection)]
            (let [stream-routes         {:default {:handler-fn #(constantly :success)}}

                  instant-queue-name    (util/prefixed-queue-name "default" (:queue-name (:instant (rabbitmq-config))))
                  instant-exchange-name (util/prefixed-queue-name "default" (:exchange-name (:instant (rabbitmq-config))))

                  delay-queue-name      (util/prefixed-queue-name "default" (:queue-name (:delay (rabbitmq-config))))
                  delay-exchange-name   (util/prefixed-queue-name "default" (:exchange-name (:delay (rabbitmq-config))))

                  dead-queue-name       (util/prefixed-queue-name "default" (:queue-name (:dead-letter (rabbitmq-config))))
                  dead-exchange-name    (util/prefixed-queue-name "default" (:exchange-name (:dead-letter (rabbitmq-config))))

                  expected-queue-status {:message-count 0 :consumer-count 0}]

              (producer/make-queues stream-routes)

              (is (= expected-queue-status (lq/status ch instant-queue-name)))
              (is (= expected-queue-status (lq/status ch delay-queue-name)))
              (is (= expected-queue-status (lq/status ch dead-queue-name)))

              (lq/delete ch instant-queue-name)
              (lq/delete ch delay-queue-name)
              (lq/delete ch dead-queue-name)
              (le/delete ch delay-exchange-name)
              (le/delete ch instant-exchange-name)
              (le/delete ch dead-exchange-name))))))

    (testing "when retries are disabled"
      (with-redefs [config/ziggurat-config (constantly (assoc ziggurat-config
                                                              :retry {:enabled false}))]
        (testing "it does not create queues when stream-routes are not passed"
          (let [counter (atom 0)]
            (with-redefs [producer/create-and-bind-queue (fn
                                                           ([_ _] (swap! counter inc))
                                                           ([_ _ _] (swap! counter inc)))]
              (producer/make-queues {:default {:handler-fn #(constantly :success)}})
              (is (= 0 @counter)))))

        (testing "it creates queues with topic entity for channels only"
          (with-open [ch (lch/open connection)]
            (let [stream-routes                  {:default {:handler-fn #(constantly :success) :channel-1 #(constantly :success)}}
                  instant-queue-suffix           (:queue-name (:instant (rabbitmq-config)))
                  instant-exchange-suffix        (:exchange-name (:instant (rabbitmq-config)))
                  delay-queue-suffix             (:queue-name (:delay (rabbitmq-config)))
                  delay-exchange-suffix          (:exchange-name (:delay (rabbitmq-config)))
                  dead-letter-queue-suffix       (:queue-name (:dead-letter (rabbitmq-config)))
                  dead-letter-exchange-suffix    (:exchange-name (:dead-letter (rabbitmq-config)))
                  prefix-name                    "default_channel_channel-1"
                  channel1-instant-queue-name    (util/prefixed-queue-name prefix-name instant-queue-suffix)
                  channel1-instant-exchange-name (util/prefixed-queue-name prefix-name instant-exchange-suffix)
                  channel1-delay-queue-name      (util/prefixed-queue-name prefix-name delay-queue-suffix)
                  channel1-delay-exchange-name   (util/prefixed-queue-name prefix-name delay-exchange-suffix)
                  channel1-dead-queue-name       (util/prefixed-queue-name prefix-name dead-letter-queue-suffix)
                  channel1-dead-exchange-name    (util/prefixed-queue-name prefix-name dead-letter-exchange-suffix)
                  expected-queue-status          {:message-count 0 :consumer-count 0}]

              (producer/make-queues stream-routes)
              (is (= expected-queue-status (lq/status ch channel1-instant-queue-name)))
              (is (= expected-queue-status (lq/status ch channel1-delay-queue-name)))
              (is (= expected-queue-status (lq/status ch channel1-dead-queue-name)))

              (lq/delete ch channel1-instant-queue-name)
              (lq/delete ch channel1-delay-queue-name)
              (lq/delete ch channel1-dead-queue-name)
              (le/delete ch channel1-delay-exchange-name)
              (le/delete ch channel1-instant-exchange-name)
              (le/delete ch channel1-dead-exchange-name))))))

    (testing "when retries are disabled"
      (with-redefs [config/ziggurat-config (constantly (assoc ziggurat-config
                                                              :retry {:enabled false}
                                                              :stream-router {:default {:channels {:channel-1 {:retry {:enabled false}}}}}))]

        (testing "it creates instant queues with topic entity for channels only"
          (with-open [ch (lch/open connection)]
            (let [stream-routes                  {:default {:handler-fn #(constantly :success) :channel-1 #(constantly :success)}}
                  instant-queue-suffix           (:queue-name (:instant (rabbitmq-config)))
                  instant-exchange-suffix        (:exchange-name (:instant (rabbitmq-config)))
                  prefix-name                    "default_channel_channel-1"
                  channel1-instant-queue-name    (util/prefixed-queue-name prefix-name instant-queue-suffix)
                  channel1-instant-exchange-name (util/prefixed-queue-name prefix-name instant-exchange-suffix)
                  expected-queue-status          {:message-count 0 :consumer-count 0}]

              (producer/make-queues stream-routes)
              (is (= expected-queue-status (lq/status ch channel1-instant-queue-name)))
              (lq/delete ch channel1-instant-queue-name)
              (le/delete ch channel1-instant-exchange-name))))))))
