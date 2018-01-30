(ns keechma.toolbox.animations.animator)

(defprotocol IAnimator
  (position [this])
  (done? [this]))

(defrecord DefaultAnimator []
  IAnimator
  (position [this]
    1)
  (done? [this]
    true))
