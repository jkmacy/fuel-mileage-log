(ns fuel-log.app
  (:require [fuel-log.model :as model]
            [clojure.string :as str]))

;; Browser application, authored in Squint rather than hand-written JavaScript.
;; IndexedDB name, object store, record fields and JSON format are unchanged.
(def db-name "fuel-mileage-local-v1")
(def store-name "entries")
(defonce db (atom nil))
(defonce entries (atom []))
(defonce editing-id (atom nil))
(defonce toast-timer (atom nil))

(defn q [selector] (.querySelector js/document selector))
(defn set-text! [selector text] (set! (.-textContent (q selector)) (str text)))
(defn set-html! [selector html] (set! (.-innerHTML (q selector)) html))
(defn safe-text [value]
  (str/escape (str (or value "")) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))
(defn uid []
  (if (and (.-crypto js/window) (.-randomUUID (.-crypto js/window)))
    (.randomUUID (.-crypto js/window))
    (str "entry-" (.now js/Date) "-" (.slice (.toString (js/Math.random) 36) 2))))
(defn fmt-n [n]
  (.toLocaleString (js/Number n) "en-US" #js {:maximumFractionDigits 1}))
(defn fmt-g [n]
  (.toLocaleString (js/Number n) "en-US" #js {:minimumFractionDigits 3 :maximumFractionDigits 3}))
(defn fmt-usd [n]
  (.toLocaleString (js/Number n) "en-US" #js {:style "currency" :currency "USD"}))

(defn toast! [message]
  (when @toast-timer (js/clearTimeout @toast-timer))
  (let [el (q "#toast")]
    (set! (.-textContent el) message)
    (.remove (.-classList el) "hidden")
    (reset! toast-timer
            (js/setTimeout (fn [] (.add (.-classList el) "hidden")) 3500))))

(defn open-db []
  (js/Promise.
    (fn [resolve reject]
      (if-not js/indexedDB
        (reject (js/Error. "IndexedDB is unavailable"))
        (let [req (.open js/indexedDB db-name 1)]
          (set! (.-onupgradeneeded req)
                (fn [_]
                  (let [database (.-result req)
                        store (.createObjectStore database store-name #js {:keyPath "id"})]
                    (.createIndex store "date" "date" #js {:unique false}))))
          (set! (.-onsuccess req) (fn [_] (resolve (.-result req))))
          (set! (.-onerror req) (fn [_] (reject (.-error req)))))))))

(defn read-records []
  (js/Promise.
    (fn [resolve reject]
      (let [tx (.transaction @db #js [store-name] "readonly")
            req (.getAll (.objectStore tx store-name))]
        (set! (.-onsuccess req) (fn [_] (resolve (js/Array.from (.-result req)))))
        (set! (.-onerror req) (fn [_] (reject (.-error req))))))))

(defn write-records! [records]
  (js/Promise.
    (fn [resolve reject]
      (let [tx (.transaction @db #js [store-name] "readwrite")
            store (.objectStore tx store-name)]
        (doseq [r records] (.put store r))
        (set! (.-oncomplete tx) (fn [_] (resolve nil)))
        (set! (.-onerror tx) (fn [_] (reject (.-error tx))))))))

(defn delete-record! [id]
  (js/Promise.
    (fn [resolve reject]
      (let [tx (.transaction @db #js [store-name] "readwrite")]
        (.delete (.objectStore tx store-name) id)
        (set! (.-oncomplete tx) (fn [_] (resolve nil)))
        (set! (.-onerror tx) (fn [_] (reject (.-error tx))))))))

(defn render-entry [r]
  (str "<article class=\"entry\"><div class=\"entry-top\"><div><div class=\"entry-time\">"
       (safe-text (model/record-date-time r))
       "</div><div style=\"font-size:12px;color:var(--muted);margin-top:5px\">"
       (if (= (:origin r) "sheet") "Imported record" "Saved on device")
       "</div></div><div class=\"entry-cost\">" (fmt-usd (:cost r)) "</div></div>"
       "<div class=\"entry-info\"><div><label>Odometer</label><strong>" (fmt-n (:mileage r))
       " mi</strong></div><div><label>Trip</label><strong>" (fmt-n (:trip r))
       " mi</strong></div><div><label>Fuel</label><strong>" (fmt-g (:gallons r))
       " gal</strong></div></div><div class=\"entry-actions\">"
       "<button class=\"mini\" data-edit=\"" (safe-text (:id r)) "\">Edit</button>"
       "<button class=\"mini delete\" data-delete=\"" (safe-text (:id r)) "\">Delete</button>"
       "</div></article>"))

(declare render-history!)
(defn show-page! [page]
  (doseq [el (.querySelectorAll js/document "[data-page]")]
    (.toggle (.-classList el) "hidden" (not= (.-page (.-dataset el)) page)))
  (doseq [el (.querySelectorAll js/document "button.tab")]
    (let [active (= (.-goto (.-dataset el)) page)]
      (.toggle (.-classList el) "active" active)
      (if active
        (.setAttribute el "aria-current" "page")
        (.removeAttribute el "aria-current"))))
  (when (= page "history") (render-history!))
  (.scrollTo js/window #js {:top 0 :behavior "instant"}))

(defn render-history! []
  (let [query (str/lower-case (str/trim (.-value (q "#search"))))
        year (.-value (q "#year-filter"))
        filtered (filter (fn [r]
                           (and (or (= year "all") (str/starts-with? (:date r) year))
                                (or (= query "")
                                    (some #(str/includes? (str/lower-case (str %)) query)
                                          [(:date r) (model/record-date-time r)
                                           (:mileage r) (:trip r) (:cost r)])))) @entries)]
    (set-text! "#history-count" (str (count filtered) " of " (count @entries) " entries"))
    (set-html! "#history-list"
               (if (seq filtered)
                 (apply str (map render-entry filtered))
                 "<div class=\"empty\">No matching records.</div>"))))

(defn refresh! []
  (swap! entries (fn [records] (vec (model/sort-entries records))))
  (set-text! "#total-entries" (fmt-n (count @entries)))
  (let [mileage (when (seq @entries) (apply max (map :mileage @entries)))
        recent (first @entries)
        years (sort #(compare %2 %1) (distinct (map #(subs (:date %) 0 4) @entries)))
        selector (q "#year-filter")
        selected (.-value selector)]
    (set-text! "#latest-mileage" (if mileage (fmt-n mileage) "—"))
    (set-text! "#last-reading-hint"
               (if mileage (str "Last logged odometer: " (fmt-n mileage) " mi")
                   "Your current odometer reading"))
    (set-html! "#recent-entry"
               (if recent (render-entry recent)
                   "<div class=\"empty\">No fill-ups yet. Add your first entry or import your history.</div>"))
    (set-html! "#year-filter"
               (str "<option value=\"all\">All years</option>"
                    (apply str (map (fn [y] (str "<option value=\"" y "\">" y "</option>")) years))))
    (set! (.-value selector) (if (some #(= % selected) years) selected "all")))
  (render-history!))

(defn clear-form! []
  (reset! editing-id nil)
  (.reset (q "#entry-form"))
  (set-text! "#form-heading" "New fill-up")
  (set-text! "#save-button" "Save fill-up")
  (.add (.-classList (q "#cancel-edit")) "hidden"))

(defn ^:async save! [event]
  (.preventDefault event)
  (if-not @db
    (toast! "Storage unavailable")
    (let [mileage (js/Number (.-value (q "#mileage")))
          trip (js/Number (.-value (q "#trip")))
          gallons (js/Number (.-value (q "#gallons")))
          cost (js/Number (.-value (q "#cost")))]
      (if-not (model/valid-values? mileage trip gallons cost)
        (toast! "Check the numbers and try again")
        (let [existing (when @editing-id (first (filter #(= (:id %) @editing-id) @entries)))
              now (js/Date.)
              updated (if existing
                        (assoc existing :mileage mileage :trip trip :gallons gallons :cost cost)
                        {:id (uid) :date (model/date-local now)
                         :recordedAt (.toISOString now) :sourceTimestamp ""
                         :mileage mileage :trip trip :gallons gallons :cost cost :origin "app"})]
          (try
            (await (write-records! [updated]))
            (reset! entries (await (read-records)))
            (clear-form!)
            (refresh!)
            (show-page! "history")
            (toast! (if existing "Entry updated" "Fill-up saved"))
            (catch :default err
              (js/console.error err)
              (toast! "Could not save entry"))))))))

(defn edit! [id]
  (when-let [r (first (filter #(= (:id %) id) @entries))]
    (reset! editing-id id)
    (doseq [name ["mileage" "trip" "gallons" "cost"]]
      (set! (.-value (q (str "#" name))) (str (get r (keyword name)))))
    (set-text! "#form-heading" "Edit fill-up")
    (set-text! "#save-button" "Save changes")
    (.remove (.-classList (q "#cancel-edit")) "hidden")
    (show-page! "add")
    (.focus (q "#mileage"))))

(defn ^:async remove! [id]
  (when-let [r (first (filter #(= (:id %) id) @entries))]
    (when (js/confirm (str "Delete the fill-up from " (model/record-date-time r) "? This cannot be undone."))
      (try
        (await (delete-record! id))
        (reset! entries (await (read-records)))
        (refresh!)
        (toast! "Entry deleted")
        (catch :default err
          (js/console.error err)
          (toast! "Could not delete entry"))))))

(defn download! [name content content-type]
  (let [blob (js/Blob. #js [content] #js {:type content-type})
        url (.createObjectURL js/URL blob)
        link (.createElement js/document "a")]
    (set! (.-href link) url)
    (set! (.-download link) name)
    (.append js/document.body link)
    (.click link)
    (.remove link)
    (js/setTimeout (fn [] (.revokeObjectURL js/URL url)) 5000)))

(defn export-csv! []
  (download! (str "fuel-mileage-" (model/date-local (js/Date.)) ".csv")
             (model/export-csv @entries) "text/csv;charset=utf-8"))

(defn export-json! []
  (download! (str "fuel-mileage-backup-" (model/date-local (js/Date.)) ".json")
             (js/JSON.stringify
               {:format "fuel-mileage-v1"
                :exportedAt (.toISOString (js/Date.))
                :entries @entries} nil 2)
             "application/json"))

(defn ^:async import-file! [event]
  (let [file (aget (.-files (.-target event)) 0)]
    (when file
      (try
        (let [body (await (.text file))
              result (if (str/ends-with? (str/lower-case (.-name file)) ".json")
                       (model/import-json body @entries uid)
                       (model/import-csv body @entries uid))
              records (:records result)]
          (when (seq records)
            (await (write-records! records))
            (reset! entries (await (read-records)))
            (refresh!))
          (set-text! "#import-status"
                     (str "Imported " (:added result) " records; " (:duplicates result)
                          " duplicates skipped; " (:invalid result) " invalid rows."))
          (toast! (str (:added result) " records imported")))
        (catch :default err
          (js/console.error err)
          (set-text! "#import-status" (str "Import error: " (.-message err)))
          (toast! "Import failed")))
      (set! (.-value (.-target event)) ""))))

(defn ^:async initialize! []
  (try
    (reset! db (await (open-db)))
    (reset! entries (await (read-records)))
    (refresh!)
    (catch :default err
      (js/console.error err)
      (doseq [el (.querySelectorAll (q "#entry-form") "input,button")]
        (set! (.-disabled el) true))
      (set-html! "#recent-entry"
                 "<div class=\"empty\">Local storage is unavailable in this browser. Try Chrome or Brave over HTTPS.</div>")
      (toast! "Storage is unavailable")))
  (let [clock (fn []
                (let [now (js/Date.)]
                  (set-text! "#clock-label"
                             (str (model/date-local now) " " (model/time-local now)))))]
    (clock)
    (js/setInterval clock 30000))
  (doseq [button (.querySelectorAll js/document "[data-goto]")]
    (.addEventListener button "click"
      (fn [_] (show-page! (.-goto (.-dataset button))))))
  (.addEventListener (q "#entry-form") "submit" save!)
  (.addEventListener (q "#cancel-edit") "click" (fn [_] (clear-form!)))
  (.addEventListener (q "#search") "input" (fn [_] (render-history!)))
  (.addEventListener (q "#year-filter") "change" (fn [_] (render-history!)))
  (.addEventListener js/document "click"
    (fn [event]
      (let [target (.-target event)
            edit-button (.closest target "[data-edit]")
            delete-button (.closest target "[data-delete]")]
        (when edit-button (edit! (.-edit (.-dataset edit-button))))
        (when delete-button (remove! (.-delete (.-dataset delete-button)))))))
  (.addEventListener (q "#import-button") "click" (fn [_] (.click (q "#file-input"))))
  (.addEventListener (q "#file-input") "change" import-file!)
  (.addEventListener (q "#export-csv") "click" (fn [_] (export-csv!)))
  (.addEventListener (q "#export-json") "click" (fn [_] (export-json!)))
  (when (and (.-serviceWorker js/navigator) (str/starts-with? (.-protocol js/location) "http"))
    (-> (.register (.-serviceWorker js/navigator) "./sw.js")
        (.catch (fn [err] (js/console.warn "Offline install unavailable" err))))))

(initialize!)
