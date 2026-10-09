package app.vela.device

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the phone's map knows that a paired nav device shows, handed across without the device
 * service reaching into the view model. The same idea as [app.vela.car.CarBridge].
 */
object DeviceBridge {
    /** The posted limit of the road under the puck, as the phone shows it. Null when unknown. */
    val speedLimitKmh = MutableStateFlow<Double?>(null)
}
