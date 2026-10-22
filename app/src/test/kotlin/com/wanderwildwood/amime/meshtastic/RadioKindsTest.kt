package com.wanderwildwood.amime.meshtastic

import com.wanderwildwood.amime.link.MeshCoreBackend
import com.wanderwildwood.amime.link.RadioKind
import com.wanderwildwood.amime.link.RecordingTransport
import com.wanderwildwood.amime.link.Session
import com.wanderwildwood.amime.mesh.Delivery
import com.wanderwildwood.amime.mesh.MeshStore
import com.wanderwildwood.amime.mesh.Person
import com.wanderwildwood.amime.protocol.AdvType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class RadioKindsTest {
    private val nus = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    @Test
    fun the_service_decides() {
        assertEquals(RadioKind.MESHTASTIC, RadioKinds.detect(listOf(MtUuids.SERVICE), "MeshCore-x"))
        assertEquals(RadioKind.MESHCORE, RadioKinds.detect(listOf(nus), "Whatever"))
        assertNull(RadioKinds.detect(listOf(UUID.randomUUID()), "MeshCore-x"))
    }

    @Test
    fun the_name_is_only_used_when_there_is_no_advertisement() {
        assertEquals(RadioKind.MESHTASTIC, RadioKinds.detect(null, "Meshtastic_ab12"))
        assertEquals(RadioKind.MESHTASTIC, RadioKinds.detect(null, "Bob_09af"))
        assertEquals(RadioKind.MESHCORE, RadioKinds.detect(null, "MeshCore-Bob"))
        assertNull(RadioKinds.detect(null, "Headphones"))
        assertNull(RadioKinds.detect(null, null))
    }

    @Test
    fun the_service_and_characteristic_ids_are_the_published_ones() {
        assertEquals("6ba1b218-15a8-461f-9fa8-5dcae273eafd", MtUuids.SERVICE.toString())
        assertEquals("f75c76d2-129e-4dad-a1dd-7866124401e7", MtUuids.TO_RADIO.toString())
        assertEquals("2c55e69e-4993-11ed-b878-0242ac120002", MtUuids.FROM_RADIO.toString())
        assertEquals("ed9da18c-a800-4f66-a670-aa7547e34453", MtUuids.FROM_NUM.toString())
        assertEquals(512, MtUuids.DESIRED_MTU)
    }

    @Test
    fun the_meshcore_backend_does_what_the_session_did_on_its_own() {
        val direct = RecordingTransport()
        Session(direct, object : Session.Listener {}).apply {
            start(); syncContacts(); refreshBattery()
        }
        val wrapped = RecordingTransport()
        val backend = MeshCoreBackend(Session(wrapped, object : Session.Listener {}))
        backend.start()
        assertEquals(RadioKind.MESHCORE, backend.kind)
        assertEquals(direct.opcodes(), wrapped.opcodes())
    }

    @Test
    fun a_meshtastic_send_settles_in_the_store_with_its_reason() {
        val store = MeshStore()
        val key = MtSession.keyOf(0x22)
        store.mtNode(Person(key, name = "Ann", type = AdvType.CHAT, pathKnown = true, lastHeard = 1))
        val id = store.mtSent(key, "hi", 1, Delivery.AWAITING_ACK, until = 99)
        store.mtSettle(key, id, Delivery.REFUSED, failure = 39)
        val m = store.state.conversations[key]!!.single()
        assertEquals(Delivery.REFUSED, m.delivery)
        assertEquals(39, m.failure)
    }
}
