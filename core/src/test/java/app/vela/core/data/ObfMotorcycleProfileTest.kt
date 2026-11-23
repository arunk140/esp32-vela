package app.vela.core.data

import net.osmand.router.RoutingConfiguration
import org.junit.Assert.assertEquals
import org.junit.Test

/** Motorcycle mode's offline profile: the vendored routing.xml builds it as the car router with its
 *  motorcycle variant switched on, which is what closes ways tagged motorcycle=no. */
class ObfMotorcycleProfileTest {
    @Test fun `the motorcycle name builds the car router with the motorcycle variant`() {
        val params = HashMap<String, String>()
        val config = RoutingConfiguration.getDefault().build("motorcycle", RoutingConfiguration.RoutingMemoryLimits(64, 16), params)
        assertEquals("true", params["profile_motorcycle"])
        assertEquals("car", config.routerName)
    }
}
