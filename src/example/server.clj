(ns example.server
  "Official Sente reference example: server"
  {:author "Peter Taoussanis (@ptaoussanis)"}

  (:gen-class :main true)

  (:require
   [clojure.string     :as str]
   [ring.middleware.defaults]
   [compojure.core     :as comp :refer (defroutes GET POST)]
   [compojure.route    :as route]
   [hiccup.core        :as hiccup]
   [clojure.core.async :as async  :refer (<! <!! >! >!! put! chan go go-loop)]
   [taoensso.encore    :as encore :refer ()]
   [taoensso.timbre    :as timbre :refer (tracef debugf infof warnf errorf)]
   [taoensso.sente     :as sente]

   ;;; TODO: choose (uncomment) a supported web server and adapter
   [org.httpkit.server :as http-kit]
   [taoensso.sente.server-adapters.http-kit :refer (sente-web-server-adapter)]
   ;;
   ;; [immutant.web :as immutant]
   ;; [taoensso.sente.server-adapters.immutant :refer (sente-web-server-adapter)]
   ;;
   ;; [nginx.clojure.embed :as nginx-clojure]
   ;; [taoensso.sente.server-adapters.nginx-clojure :refer (sente-web-server-adapter)]

   ;; Optional, for Transit encoding:
   [taoensso.sente.packers.transit :as sente-transit]))

;; (timbre/set-level! :trace) ; Uncomment for more logging

;;;; TODO: choose (uncomment) the relevant server fn

(defn start-selected-web-server! [ring-handler port]
  (infof "Starting http-kit...")
  (let [stop-fn (http-kit/run-server ring-handler {:port port})]
    {:server  nil ; http-kit doesn't expose this
     :port    (:local-port (meta stop-fn))
     :stop-fn (fn [] (stop-fn :timeout 100))}))

;; (defn start-selected-web-server! [ring-handler port]
;;   (infof "Starting Immutant...")
;;   (let [server (immutant/run ring-handler :port port)]
;;     {:server  server
;;      :port    (:port server)
;;      :stop-fn (fn [] (immutant/stop server))}))

;; (defn start-selected-web-server! [ring-handler port]
;;   (infof "Starting nginx-clojure...")
;;   (let [port (nginx-clojure/run-server ring-handler {:port port})]
;;     {:server  nil ; nginx-clojure doesn't expose this
;;      :port    port
;;      :stop-fn nginx-clojure/stop-server}))

;;;; Define our Sente channel socket (chsk) server

(let [;; Serializtion format, must use same val for client + server:
      packer :edn ; Default packer, a good choice in most cases
      ;; (sente-transit/get-flexi-packer :edn) ; Experimental, needs Transit dep

      {:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn
              connected-uids]}
      (sente/make-channel-socket-server! sente-web-server-adapter
        {:packer packer})]

  (def ring-ajax-post                ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                       ch-recv) ; ChannelSocket's receive channel
  (def chsk-send!                    send-fn) ; ChannelSocket's send API fn
  (def connected-uids                connected-uids) ; Watchable, read-only atom
  )

;;;; Ring handlers

(defn landing-pg-handler [ring-req]
  (hiccup/html
    [:h1 "Sente reference example"]
    [:p "An Ajax/WebSocket" [:strong " (random choice!)"] " has been configured for this example"]
    [:hr]
    [:p [:strong "Step 1: "] " try hitting the buttons:"]
    [:button#btn1 {:type "button"} "chsk-send! (w/o reply)"]
    [:button#btn2 {:type "button"} "chsk-send! (with reply)"]
    ;;
    [:p [:strong "Step 2: "] " observe std-out (for server output) and below (for client output):"]
    [:textarea#output {:style "width: 100%; height: 200px;"}]
    ;;
    [:hr]
    [:h2 "Step 3: try login with a user-id"]
    [:p  "The server can use this id to send events to *you* specifically."]
    [:p
     [:input#input-login {:type :text :placeholder "User-id"}]
     [:button#btn-login {:type "button"} "Secure login!"]]
    ;;
    [:hr]
    [:h2 "Step 4: want to re-randomize Ajax/WebSocket connection type?"]
    [:p "Hit your browser's reload/refresh button"]
    [:script {:src "main.js"}] ; Include our cljs target
    ))

(defn login-handler
  "Here's where you'll add your server-side login/auth procedure (Friend, etc.).
  In our simplified example we'll just always successfully authenticate the user
  with whatever user-id they provided in the auth request."
  [ring-req]
  (let [{:keys [session params]} ring-req
        {:keys [user-id]} params]
    (debugf "Login request: %s" params)
    {:status 200 :session (assoc session :uid user-id)}))

(defroutes ring-routes
  (GET  "/"      ring-req (landing-pg-handler            ring-req))
  (GET  "/chsk"  ring-req (ring-ajax-get-or-ws-handshake ring-req))
  (POST "/chsk"  ring-req (ring-ajax-post                ring-req))
  (POST "/login" ring-req (login-handler                 ring-req))
  (route/resources "/") ; Static files, notably public/main.js (our cljs target)
  (route/not-found "<h1>Page not found</h1>"))

(def main-ring-handler
  "**NB**: Sente requires the Ring `wrap-params` + `wrap-keyword-params`
  middleware to work. These are included with
  `ring.middleware.defaults/wrap-defaults` - but you'll need to ensure
  that they're included yourself if you're not using `wrap-defaults`."
  (ring.middleware.defaults/wrap-defaults
    ring-routes (assoc ring.middleware.defaults/site-defaults :proxy true)))

;;;; Sente event handlers

(defmulti -event-msg-handler
  "Multimethod to handle Sente `event-msg`s"
  :id ; Dispatch on event-id
  )

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (-event-msg-handler ev-msg))

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)]
    (debugf "Unhandled event: %s" event)
    (when ?reply-fn
      (?reply-fn {:umatched-event-as-echoed-from-from-server event}))))

;; TODO Add your (defmethod -event-msg-handler <event-id> [ev-msg] <body>)s here...

;;;; Sente event router (our `event-msg-handler` loop)

(defonce router_ (atom nil))
(defn  stop-router! [] (when-let [stop-f @router_] (stop-f)))
(defn start-router! []
  (stop-router!)
  (reset! router_
    (sente/start-server-chsk-router!
      ch-chsk event-msg-handler)))

;;;; Some server>user async push examples

(defn start-example-broadcaster!
  "As an example of server>user async pushes, setup a loop to broadcast an
  event to all connected users every 10 seconds"
  []
  (let [broadcast!
        (fn [i]
          (debugf "Broadcasting server>user: %s" @connected-uids)
          (doseq [uid (:any @connected-uids)]
            (chsk-send! uid
              [:some/broadcast
               {:what-is-this "An async broadcast pushed from server"
                :how-often "Every 10 seconds"
                :to-whom uid
                :i i}])))]

    (go-loop [i 0]
      (<! (async/timeout 10000))
      (broadcast! i)
      (recur (inc i)))))

(defn test-fast-server>user-pushes
  "Quickly pushes 100 events to all connected users. Note that this'll be
  fast+reliable even over Ajax!"
  []
  (doseq [uid (:any @connected-uids)]
    (doseq [i (range 100)]
      (chsk-send! uid [:fast-push/is-fast (str "hello " i "!!")]))))

(comment (test-fast-server>user-pushes))

;;;; Init stuff

(defonce    web-server_ (atom nil)) ; {:server _ :port _ :stop-fn (fn [])}
(defn  stop-web-server! [] (when-let [m @web-server_] ((:stop-fn m))))
(defn start-web-server! [& [port]]
  (stop-web-server!)
  (let [{:keys [stop-fn port] :as server-map}
        (start-selected-web-server! (var main-ring-handler)
          (or port 0) ; 0 => auto (any available) port
          )
        uri (format "http://localhost:%s/" port)]
    (infof "Web server is running at `%s`" uri)
    (try
      (.browse (java.awt.Desktop/getDesktop) (java.net.URI. uri))
      (catch java.awt.HeadlessException _))
    (reset! web-server_ server-map)))

(defn stop!  []  (stop-router!)  (stop-web-server!))
(defn start! [] (start-router!) (start-web-server! 8080) (start-example-broadcaster!))
;; (defonce _start-once (start!))

(defn -main "For `lein run`, etc." [] (start!))

(comment
  (start!)
  (test-fast-server>user-pushes))
