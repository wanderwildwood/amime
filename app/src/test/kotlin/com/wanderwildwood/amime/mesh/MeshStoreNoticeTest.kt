package com.wanderwildwood.amime.mesh

import com.wanderwildwood.amime.meshtastic.MtAdminReport
import com.wanderwildwood.amime.meshtastic.Restart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A notice about a write belongs to the screen where it was made. */
class MeshStoreNoticeTest {
    private val store = MeshStore(now = { 0L })
    private val pending = MtAdminReport(MtAdminReport.State.PENDING, "Hop limit = 4 hops")
    private val saved = MtAdminReport(MtAdminReport.State.SAVED, "Hop limit = 4 hops", restart = Restart.NO)

    @Test
    fun a_settings_notice_is_not_shown_on_channels() {
        store.noticeScreen(NoticeScreen.SETTINGS)
        store.mtAdmin(pending)
        store.mtAdmin(saved)
        assertEquals(saved, store.state.adminNoticeFor(NoticeScreen.SETTINGS))
        assertNull(store.state.adminNoticeFor(NoticeScreen.CHANNELS))
    }

    @Test
    fun leaving_the_screen_clears_the_notice_for_good() {
        store.noticeScreen(NoticeScreen.SETTINGS)
        store.mtAdmin(pending)
        store.mtAdmin(saved)
        store.noticeScreen(NoticeScreen.CHANNELS)
        store.noticeScreen(NoticeScreen.SETTINGS)
        assertNull(store.state.adminNoticeFor(NoticeScreen.SETTINGS))
        assertNull(store.state.mtAdmin)
    }

    @Test
    fun a_write_waiting_survives_the_trip_but_its_late_answer_is_not_shown_elsewhere() {
        store.noticeScreen(NoticeScreen.SETTINGS)
        store.mtAdmin(pending)
        store.noticeScreen(NoticeScreen.OTHER)
        store.mtAdmin(saved)
        assertNull(store.state.adminNoticeFor(NoticeScreen.OTHER))
        assertNull(store.state.adminNoticeFor(NoticeScreen.SETTINGS))
        assertNull(store.state.adminNoticeFor(NoticeScreen.CHANNELS))
    }

    @Test
    fun a_new_action_elsewhere_takes_the_notice_with_it() {
        store.noticeScreen(NoticeScreen.SETTINGS)
        store.mtAdmin(pending)
        store.mtAdmin(saved)
        store.noticeScreen(NoticeScreen.CHANNELS)
        store.mtAdmin(pending.copy(label = "Channel"))
        assertNull(store.state.adminNoticeFor(NoticeScreen.SETTINGS))
        assertEquals("Channel", store.state.adminNoticeFor(NoticeScreen.CHANNELS)!!.label)
    }

    @Test
    fun staying_on_the_screen_keeps_the_notice_through_the_answer() {
        store.noticeScreen(NoticeScreen.CHANNELS)
        store.mtAdmin(pending)
        store.noticeScreen(NoticeScreen.CHANNELS)
        store.mtAdmin(saved)
        assertEquals(saved, store.state.adminNoticeFor(NoticeScreen.CHANNELS))
    }
}
