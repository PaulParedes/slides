(ns main
  (:require [nrepl.server :as nrepl])
  (:import [noria.scene PhotonApi Point Size Color]))

(defn consume-events [evts]
  (prn evts))

(defn -main []
  (assert (java.awt.GraphicsEnvironment/isHeadless))
  (defonce server (nrepl/start-server :port 9898))

  (PhotonApi/runEventLoop
    (reify java.util.function.Consumer
           (accept [this evts]
                   (consume-events evts)))))

(comment




  (def scene (noria.scene.SceneImpl.))


  (let [root-id 1
        rect-ids (long-array (range 2 20))]
    (doseq [i (range 2 20)]
      (.rect scene i (Point. 0 (* 20 i)) (Size. 10 10) (Color. 1 0 0 1)))
    (.stack scene root-id rect-ids)
    (.setRoot scene root-id)
    (.commit scene 0)
    )


  (do
    (doseq [i (range 2 20)]
      (.rect scene i (Point. 90 (* 20 i)) (Size. 10 10) (Color. 1 0 0 1)))
    (.commit scene 0)
    )

  (do

    (.rect scene 100 (Point. 100 100) (Size. 100 100) (Color. 0 0 0 1))
    (.setRoot scene 100)
    (.commit scene 0))





  )
