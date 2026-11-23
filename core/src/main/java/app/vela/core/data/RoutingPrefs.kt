package app.vela.core.data

/**
 * Routing preferences the app sets and the engine reads. Kept as a plain holder rather than
 * threaded through every directions() caller (the chooser, the nav session's reroutes and
 * rechecks, the car app) because a preference is a fact about the user, not about one request.
 */
object RoutingPrefs {
    /** Bike mode routes for safety over speed (issue #401): the offline engine's bicycle profile
     *  where a region is downloaded, else the open Valhalla router told to stay off busy roads.
     *  Off = the plain fastest bike route from OSRM. Default on, like Google's bike routing. */
    @Volatile var bikeSafe: Boolean = true

    /** The route chooser's sticky avoid toggles (drive only). The NAV SESSION reads these for its
     *  own fetches (reroutes, rechecks, added stops, route naming): they used to call directions()
     *  with the defaults, so a reroute on a drive planned with Avoid tolls could put the tolls
     *  straight back (found 2026-09-16). MapViewModel keeps them in step with the prefs. */
    @Volatile var avoidTolls: Boolean = false
    @Volatile var avoidHighways: Boolean = false
    @Volatile var avoidFerries: Boolean = false

    /** Motorcycle mode (drive only): routes keep off roads OpenStreetMap closes to motorcycles
     *  (`motorcycle=no`, `motor_vehicle=no` and the like). Google's keyless directions have no such
     *  mode, so a motorcycle trip is the downloaded region's motorcycle profile, else Valhalla's
     *  `motorcycle` costing (SPEC 4.3, Motorcycle mode). The app sets it from the route card's chip
     *  while Settings > Navigation shows that chip. */
    @Volatile var motorcycle: Boolean = false

    /** False: directions skip Google (open router only, no live traffic). Set per trip by the app. */
    @Volatile var googleTraffic: Boolean = true
}
