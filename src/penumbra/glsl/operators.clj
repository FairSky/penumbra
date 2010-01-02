;;   Copyright (c) Zachary Tellman. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns penumbra.glsl.operators
  (:use [penumbra opengl slate])
  (:use [penumbra.opengl
         (texture :only (create-texture release! texture?))
         (framebuffer :only (pixel-format write-format))])
  (:use [penumbra.glsl core data])
  (:use [penumbra.translate core operators])
  (:use [clojure.contrib
         (seq-utils :only (separate indexed flatten))
         (def :only (defvar- defn-memo))])
  (:require [clojure.zip :as zip])
  (:require [penumbra.translate.c :as c]))

;;;

(defn- typecast-float4 [x]
  (condp = (:tag (meta x))
    :float   (list 'float4 x)
    :float2  (list 'float4 x 1.0 1.0)
    :float3  (list 'float4 x 1.0)
    :float4  x
    :color   (list 'float4 x)
    :color2  (list 'float4 x 1.0 1.0)
    :color3  (list 'float4 x 1.0)
    :color4  x
    :int     (list 'float4 x)
    :int2    (list 'float4 x 1 1)
    :int3    (list 'float4 x 1)
    :int4    x
    nil      (throw (Exception. (str "Cannot typecast \n" (with-out-str (print-tree x)))))))

(defvar- type-format
  {:color :unsigned-byte
   :color2 :unsigned-byte
   :color3 :unsigned-byte
   :color4 :unsigned-byte
   :float :float       
   :float2 :float
   :float3 :float
   :float4 :float
   :int :int
   :int2 :int
   :int3 :int
   :int4 :int})

(defvar- texture-tuple
  {:float 1
   :float2 2
   :float3 3
   :float4 4
   :int 1
   :int2 2
   :int3 3
   :int4 4})

(defvar- texture-type
  {[:unsigned-byte 1] :float
   [:unsigned-byte 2] :float2
   [:unsigned-byte 3] :float3
   [:unsigned-byte 4] :float4
   [:float 1] :float
   [:float 2] :float2
   [:float 3] :float3
   [:float 4] :float4
   [:int 1] :int
   [:int 2] :int2
   [:int 3] :int3
   [:int 4] :int4})

(defvar- swizzle { 1 '.x, 2 '.xy, 3 '.xyz, 4 '.xyzw })

;;;

(defn typeof-element [e]
  (texture-type [(:internal-type e) (:tuple e)]))

(defn typeof-param [p]
  (if (number? p)
    (if (int? p) :int :float)
    (keyword (str (if (-> p first int?) "int" "float") (count p)))))

(defn-memo rename-element [idx]
  (symbol (str "-tex" idx)))

(defn transform-element [e]
  (if (element? e)
    (let [location (when-not (symbol? e) (last e))
          element (if (symbol? e) e (first e))]
      (list
       (swizzle (texture-tuple (typeof e)))
       (concat
        (list 'texture2DRect (-> element element-index rename-element))
        (list
         (cond
          (symbol? e)
          :coord
          (= :float2 (typeof location))
          location
          (= :float (typeof location))
          (list 'float2
                (list 'floor (list 'mod location (list '.x (list 'dim element))))
                (list 'floor (list 'div location (list '.x (list 'dim element)))))
          (= :int (typeof location))
          (list 'float2
                (list 'floor (list 'mod (list 'float location) (list '.x (list 'dim element))))
                (list 'floor (list 'div (list 'float location) (list '.x (list 'dim element)))))
          :else
          (println "Don't recognize index type" location (typeof location)))))))))

(defn- transform-dim [x]
  (let [idx (element-index (second x))]
    (add-meta (symbol (str "-dim" idx)) :tag :float2)))

(defmacro with-glsl [& body]
  `(binding [*typeof-param* typeof-param
             *typeof-element* typeof-element
             *typeof-dim* (constantly :float2)
             *dim-element* :dim
             *transformer* transformer,
             *generator* generator,
             *inspector* inspector,
             *tagger* c/tagger]
     ~@body))

;;;

(defvar- fixed-transform
  '((<- -coord (-> :multi-tex-coord0 .xy (* -dim)))
    (<- :position (* :model-view-projection-matrix :vertex))))

(defn- wrap-uniform
  ([x] (list 'declare (list 'uniform x)))
  ([x type] (list 'declare (list 'uniform (add-meta x :tag type)))))

(defn- prepend-index
  "Adds an -index variable definition to beginning of program if :index is used anywhere inside"
  [x]
  (let [index
        '((<-
          -index
          (-> -coord .y floor (* (.x -dim)) (+ (-> -coord .x floor)))))]
    (if ((set (flatten x)) :index)
      (concat
        index
        (apply-transforms [(replace-with :index #^:float '-index)] x))
      x)))

(defn- frag-data-typecast
  "Tranforms the final expression into one or more assignments to gl_FragData[n]"
  [results]
  (list* 'do
         (map
          (fn [[idx e]]
            (list '<- (list '-> :frag-data (list 'nth idx)) (typecast-float4 e)))
          (indexed results))))

(defn- wrap-and-prepend
  "Defines -coord and -dim, and applies prepend-index"
  [x]
  (list
   '(do
      (declare (varying #^:float2 -coord))
      (declare (uniform #^:float2 -dim))
      (declare (uniform #^:float2 -bounds)))
   (list 'defn 'void 'main [] (prepend-index x))))             

(defn- create-operator
  ([body]
     (create-literal-program
      "#extension GL_ARB_texture_rectangle : enable"
      (wrap-and-prepend fixed-transform)
      body)))

(defn- post-process
  "Transforms the body, and pulls out all the relevant information."
  [program]
  (let [[elements params] (separate element? (tree-filter #(and (symbol? %) (typeof %)) program))
        elements (set (map element-index elements))
        locals (filter #(:assignment (meta %)) params)
        privates (filter #(and (symbol? %) (= \- (first (name %)))) params)
        params (remove (set (concat locals privates)) (distinct params))
        declarations (list
                      'do
                      (map #(wrap-uniform (rename-element %) :sampler2DRect) elements)
                      (map #(wrap-uniform (symbol (str "-dim" %)) :float2) (range 0 (count elements)))
                      (map #(wrap-uniform %) (distinct params)))
       body (->>
             program
             (tree-map #(when (first= % 'dim) (transform-dim %)))
             (apply-element-transform transform-element)
             (apply-transforms
              (list
               #(when (first= % 'dim) (transform-dim %))
               (replace-with :coord #^:float2 '-coord)
               (replace-with :dim #^:float2 '-dim)))
             (transform-results frag-data-typecast)
             wrap-and-prepend)]
    (list 'do declarations body)))

(defn- operator-cache
  "Returns or creates the appropriate shader program for the types"
  []
  (let [programs (atom {})]
    (fn [processed-operator]
      (let [sig (:signature processed-operator)]
        (if-let [value (@programs sig)]
          value
          (let [program ((:yield-program processed-operator))
                program (->> program post-process create-operator)]
            (swap! programs #(assoc % sig program))
            program))))))

(defn-memo param-lookup [n]
  (keyword (name n)))

(defn set-params [params]
  (doseq [[n v] params]
    (apply
      uniform
      (list*
        (param-lookup n)
        (seq-wrap v)))))

;;;

(defn- create-write-texture
  "Given the type (float4, etc.), creates the appropriate target texture"
  [typecast dim]
  (let [tuple  (type-tuple typecast)
        format (type-format typecast)
        i-f    (write-format format tuple)
        p-f    (pixel-format tuple)]
    (if (nil? i-f)
      (throw (Exception. (str "Your graphics hardware does not support writing to texture of type=" format ", tuple=" tuple))))
    (create-texture :texture-rectangle dim (first i-f) p-f format tuple)))

(defn- run-map
  "Executes the map"
  [program info]
  (let [dim (:dim info)
        elements (:elements info)
        params (:params info)
        results ((:yield-results info))
        targets (map #(create-write-texture % dim) results)]
    (set-params params)
    (apply uniform (list* :_dim (map float dim)))
    (doseq [[idx d] (indexed (map :dim elements))]
      (apply uniform (list* (symbol (str "-dim" idx)) (map float d))))
    (attach-textures
      (interleave (map rename-element (range (count elements))) elements)
      targets)
    (apply draw dim)
    (doseq [e (distinct elements)]
      (if (not (:persist (meta e)))
        (release! e)))
    (if (= 1 (count targets)) (first targets) (vec targets))))

(defn create-map-template
  "Creates a template for a map, which will lazily create a set of shader programs based on the types passed in."
  [x]
  (let [cache (operator-cache)
        to-symbol (memoize #(symbol (name %)))]
    (fn [& args]
      (with-glsl
        (let [processed-map (apply process-map (cons x args))
              program (cache processed-map)]
          (with-program program
            (run-map program processed-map)))))))

;;;;;;;;;;;;;;;;;;

(defn- run-reduce
  [info]
  (let [params (:params info)
        data (first (:elements info))]
    (set-params params)
    (attach-textures [] [data])
    (loop [dim (:dim data), input data]
      (if (= [1 1] dim)
        (let [result (unwrap-first! input)]
          (release! input)
          (seq result))
        (let [half-dim  (map #(Math/ceil (/ % 2.0)) dim)
              target    (mimic-texture input half-dim)
              [w h]     half-dim
              bounds    (map #(* 2 (Math/floor (/ % 2.0))) dim)]
          (apply uniform (list* :_bounds bounds))
          (apply uniform (list* :_dim half-dim))
          (attach-textures [:_tex0 input] [target])
          (draw 0 0 w h)
          (if (not (:persist (meta input)))
            (release! input))
          (recur half-dim target))))))

(defn create-reduce-template
  "Creates a template for a reduce, which will lazily create a set of shader programs based on the types passed in."
  [x]
  (let [cache (operator-cache)
        to-symbol (memoize #(symbol (name %)))]
    (fn [& args]
      (with-glsl
        (let [processed-reduce (apply process-reduce (cons x args))
              program (cache processed-reduce)]
          (with-program program
            (run-reduce processed-reduce)))))))

;;;


