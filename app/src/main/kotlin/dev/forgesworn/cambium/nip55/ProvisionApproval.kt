package dev.forgesworn.cambium.nip55

/** One visible approval, bound to the original caller, intent and connection
 * by [current]. The worker may outlive its Activity; its authority may not. */
internal class ProvisionApproval(
    private val request: RendezvousProvisionRequest,
    private val current: () -> Boolean,
) {
    private var floor = 0L
    private var dispatched = false
    private var released = false
    private fun live(now: Long): Boolean {
        if (now < floor) return false
        floor = now
        return request.validAt(now) && current()
    }
    @Synchronized fun dispatch(now: Long): Boolean {
        if (!live(now) || dispatched) return false
        dispatched = true
        return true
    }
    @Synchronized fun current(now: Long): Boolean = live(now) && dispatched && !released
    @Synchronized fun release(envelope: String, now: Long): Boolean {
        if (!live(now) || !dispatched || released || !request.acceptsEnvelope(envelope, now)) return false
        released = true
        return true
    }
}
