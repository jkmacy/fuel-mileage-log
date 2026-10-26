(ns fuel-log.model
  (:require [clojure.string :as str]))

;; Values remain plain JS objects, as in the original application. Do not alter
;; the persistent field names without an explicit IndexedDB migration.
(defn pad [n] (.padStart (str n) 2 "0"))
(defn date-local [date]
  (str (.getFullYear date) "-" (pad (inc (.getMonth date))) "-" (pad (.getDate date))))
(defn time-local [date]
  (str (pad (.getHours date)) ":" (pad (.getMinutes date))))
(defn finite? [x] (js/Number.isFinite x))
(defn parse-number [value]
  (js/Number (str/replace (str (or value "")) #"[$,\s]" "")))

(defn parse-date [value]
  (let [s (str/trim (str (or value "")))
        iso (re-matches #"\d{4}-\d{2}-\d{2}" s)
        m (re-matches #"(\d{1,2})/(\d{1,2})/(\d{4})" s)]
    (cond
      iso (let [d (js/Date. (str s "T12:00:00"))]
            (when (and (finite? (.getTime d)) (= (date-local d) s)) s))
      m (let [month (js/Number (nth m 1))
              day (js/Number (nth m 2))
              year (js/Number (nth m 3))
              d (js/Date. year (dec month) day)]
          (when (and (= (.getFullYear d) year)
                     (= (.getMonth d) (dec month))
                     (= (.getDate d) day))
            (date-local d)))
      :else nil)))

(defn valid-values? [mileage trip gallons cost]
  (and (every? finite? [mileage trip gallons cost])
       (>= mileage 0) (>= trip 0) (> gallons 0) (> cost 0)))

(defn fingerprint [record]
  (str (:date record) "|" (:mileage record) "|" (:trip record) "|"
       (:gallons record) "|" (:cost record)))

(defn record-date-time [record]
  (let [recorded-at (:recordedAt record)
        source (or (:sourceTimestamp record) "")]
    (cond
      (and recorded-at (finite? (.getTime (js/Date. recorded-at))))
      (let [d (js/Date. recorded-at)] (str (date-local d) " " (time-local d)))
      (re-matches #"(\d{1,2})/(\d{1,2})/(\d{4})\s+(\d{1,2}):(\d{2})(?::\d{2})?" source)
      (let [[_ month day year hour minute]
            (re-matches #"(\d{1,2})/(\d{1,2})/(\d{4})\s+(\d{1,2}):(\d{2})(?::\d{2})?" source)]
        (str year "-" (pad month) "-" (pad day) " " (pad hour) ":" minute))
      :else (:date record))))

(defn sort-entries [records]
  (sort (fn [a b]
          (let [dates (compare (:date b) (:date a))]
            (if (zero? dates)
              (compare (or (:recordedAt b) (:sourceTimestamp b) "")
                       (or (:recordedAt a) (:sourceTimestamp a) ""))
              dates))) records))

;; RFC 4180-like parsing: quoted commas, CRLF, escaped quotes, and blank lines.
(defn csv-rows [s]
  (let [rows (atom []) cells (atom []) cell (atom "") quoted? (atom false)
        n (count s)]
    (letfn [(finish-cell! [] (swap! cells conj @cell) (reset! cell ""))
            (finish-row! [] (finish-cell!)
              (when (some #(not= % "") @cells) (swap! rows conj @cells))
              (reset! cells []))]
      (loop [i 0]
        (if (< i n)
          (let [c (subs s i (inc i))
                next-c (when (< (inc i) n) (subs s (inc i) (+ i 2)))]
            (cond
              (= c "\"") (if (and @quoted? (= next-c "\""))
                              (do (swap! cell str "\"") (recur (+ i 2)))
                              (do (swap! quoted? not) (recur (inc i))))
              (and (= c ",") (not @quoted?)) (do (finish-cell!) (recur (inc i)))
              (and (or (= c "\n") (= c "\r")) (not @quoted?))
              (do (finish-row!) (recur (if (and (= c "\r") (= next-c "\n")) (+ i 2) (inc i))))
              :else (do (swap! cell str c) (recur (inc i)))))
          (do
            (when @quoted? (throw (js/Error. "CSV has an unclosed quote")))
            (finish-row!)
            @rows))))))

(defn quote-csv [value]
  (let [s (str (or value ""))]
    (if (re-find #"[\",\n\r]" s)
      (str "\"" (str/replace s "\"" "\"\"") "\"")
      s)))

(defn export-csv [entries]
  (let [headers ["Recorded At" "Date" "Current Mileage" "Trip Miles" "Gallons"
                 "Cost" "Source Timestamp" "Origin"]
        rows (cons headers
                   (map (fn [e]
                          [(or (:recordedAt e) "") (:date e) (:mileage e)
                           (:trip e) (:gallons e) (:cost e)
                           (or (:sourceTimestamp e) "") (or (:origin e) "app")]) entries))]
    (str (str/join "\r\n" (map (fn [row] (str/join "," (map quote-csv row))) rows)) "\r\n")))

(defn- field [headers row names]
  (or (some (fn [name]
              (let [idx (.indexOf headers name)]
                (when (>= idx 0) (or (nth row idx nil) "")))) names) ""))

(defn- make-record [date mileage trip gallons cost recorded-at source origin id]
  {:id id :date date :recordedAt recorded-at :sourceTimestamp source
   :mileage mileage :trip trip :gallons gallons :cost cost :origin origin})

(defn- timestamp [raw]
  (when (and raw (not= "" raw))
    (let [d (js/Date. raw)]
      (when (finite? (.getTime d)) (.toISOString d)))))

(defn import-csv [text existing id-fn]
  (let [rows (csv-rows (str/replace text #"^\uFEFF" ""))
        header (mapv #(str/lower-case (str/trim %)) (first rows))
        seen (js/Set. (into-array (map fingerprint existing)))
        added (atom []) duplicates (atom 0) invalid (atom 0)]
    (when (< (count rows) 2) (throw (js/Error. "No data rows found")))
    (doseq [name ["current mileage" "trip miles" "gallons" "cost"]]
      (when-not (some #{name} header) (throw (js/Error. (str "Missing column: " name)))))
    (doseq [row (rest rows)]
      (let [date (parse-date (field header row ["date"]))
            mileage (parse-number (field header row ["current mileage"]))
            trip (parse-number (field header row ["trip miles"]))
            gallons (parse-number (field header row ["gallons"]))
            cost (parse-number (field header row ["cost"]))]
        (if (and date (valid-values? mileage trip gallons cost))
          (let [record (make-record date mileage trip gallons cost
                                    (timestamp (field header row ["recorded at" "recordedat"]))
                                    (str/trim (field header row ["source timestamp" "timestamp"]))
                                    (or (not-empty (field header row ["origin"])) "sheet")
                                    (id-fn))
                fp (fingerprint record)]
            (if (.has seen fp)
              (swap! duplicates inc)
              (do (.add seen fp) (swap! added conj record))))
          (swap! invalid inc))))
    {:records @added :added (count @added) :duplicates @duplicates :invalid @invalid}))

(defn import-json [text existing id-fn]
  (let [payload (js/JSON.parse text)
        seen (js/Set. (into-array (map fingerprint existing)))
        added (atom []) duplicates (atom 0) invalid (atom 0)]
    (when-not (and (= "fuel-mileage-v1" (.-format payload)) (js/Array.isArray (.-entries payload)))
      (throw (js/Error. "Unrecognized JSON backup")))
    (doseq [r (.-entries payload)]
      (let [date (parse-date (.-date r))
            mileage (js/Number (.-mileage r))
            trip (js/Number (.-trip r))
            gallons (js/Number (.-gallons r))
            cost (js/Number (.-cost r))]
        (if (and date (valid-values? mileage trip gallons cost))
          (let [record (make-record date mileage trip gallons cost
                                    (timestamp (.-recordedAt r))
                                    (str (or (.-sourceTimestamp r) ""))
                                    (if (= "sheet" (.-origin r)) "sheet" "app")
                                    (id-fn))
                fp (fingerprint record)]
            (if (.has seen fp)
              (swap! duplicates inc)
              (do (.add seen fp) (swap! added conj record))))
          (swap! invalid inc))))
    {:records @added :added (count @added) :duplicates @duplicates :invalid @invalid}))
