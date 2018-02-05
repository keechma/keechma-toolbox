(ns keechma.toolbox.animations.helpers
  (:require [garden.color :as color]
            [garden.units :as units]))

(defn rgb->hex [[r g b]]
  (color/rgb->hex {:red r :green g :blue b}))

(defn hex->rgb [hex]
  (let [{:keys [red green blue]} (color/hex->rgb hex)]
    [red green blue]))

(defn map-value-in-range
  ([value to-low to-high] (map-value-in-range value to-low to-high 0 1))
  ([value to-low to-high from-low] (map-value-in-range value to-low to-high from-low 1))
  ([value to-low to-high from-low from-high]
   (let [from-range-size (- from-high from-low)
         to-range-size (- to-high to-low)
         value-scale (/ (- value from-low) from-range-size)]
     (+ to-low (* value-scale to-range-size)))))

(defn interpolate-color
  ([value start-color end-color]
   (interpolate-color value start-color end-color 0))
  ([value start-color end-color from-low]
   (interpolate-color value start-color end-color from-low 1))
  ([value start-color end-color from-low from-high]
   (interpolate-color value start-color end-color from-low from-high false))
  ([value start-color end-color from-low from-high rgb?]
   (let [[start-r start-g start-b] (hex->rgb start-color)
         [end-r end-g end-b] (hex->rgb end-color)
         r (map-value-in-range value start-r end-r)
         g (map-value-in-range value start-g end-g)
         b (map-value-in-range value start-b end-b)]
     (if rgb?
       (str "rgb(" r "," g "," b ")")
       (rgb->hex (map #(.round js/Math (min 255 (max % 0))) [r g b]))))))

(defn extract-css-unit [value]
  (if-let [unit (units/read-unit value)]
    {:value (:magnitude unit) :unit (name (:unit unit)) :animatable :unit}
    {:value value :animatable false}))

(defn prepare-values [style]
  (reduce-kv
   (fn [m k v]
     (assoc m k
            (cond
              (and (string? v) (color/hex? v)) {:value v :animatable :color}
              (string? v) (extract-css-unit v)
              (number? v) {:value v :animatable :number}
              :else {:value v :animatable false}))) {} style))

(defn select-keys-by-namespace
  ([data] (select-keys-by-namespace data nil))
  ([data ns]
   (let [ns (keyword ns)]
     (reduce-kv (fn [m k v]
                  (let [key-ns (keyword (namespace k))]
                    (if (= key-ns ns)
                      (assoc m (keyword (name k)) v)
                      m))) {} data))))

(defn start-end-values [start end]
  (reduce-kv (fn [m k v]
               (let [end-value (get end k)
                     start-value (or v end-value)]
                 (assoc m k {:start start-value :end end-value}))) {} start))

(defn identity-value [_ _ end]
  end)

(defn calculate-value [value start end]
  (let [start-value (or (:value start) (:value end))
        end-value (or (:value end) (:value start))
        animatable (:animatable start)
        calculator (cond
                     (= start-value end-value) identity-value
                     (= :color animatable) interpolate-color
                     (or (= :unit animatable) (= :number animatable)) map-value-in-range 
                     :else identity-value)
        new-value (calculator value start-value end-value)]
    (if (= :unit animatable)
      (str new-value (:unit start))
      new-value)))

(defn get-current-styles
  ([value styles] (get-current-styles value styles false))
  ([value styles done?]
   (reduce-kv
    (fn [m k {:keys [start end]}]
      (assoc m k (if (:animatable start)
                   (calculate-value value start end)
                   (if done? (:value end) (:value start)))))
    {} styles)))
