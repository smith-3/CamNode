package com.fragmind.camnode.core.data

import androidx.datastore.core.DataStoreFactory
import com.fragmind.camnode.core.data.store.StoredStateSerializer
import com.fragmind.camnode.core.model.AccessRole
import com.fragmind.camnode.core.model.AppDisguise
import com.fragmind.camnode.core.model.PinCheck
import com.fragmind.camnode.core.model.CameraEvent
import com.fragmind.camnode.core.model.CameraEventType
import com.fragmind.camnode.core.model.Household
import com.fragmind.camnode.core.model.NodeId
import com.fragmind.camnode.core.model.StreamProfile
import com.fragmind.camnode.core.model.CameraLayout
import com.fragmind.camnode.core.data.device.DeviceClass
import com.fragmind.camnode.core.model.HouseholdId
import com.fragmind.camnode.core.model.HouseholdSecret
import com.fragmind.camnode.core.model.Invite
import com.fragmind.camnode.core.model.Directory
import com.fragmind.camnode.core.model.NodeEndpoint
import com.fragmind.camnode.core.model.NodeRole
import com.fragmind.camnode.core.model.WallClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalStateRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private var now = 1_000L
    private var lowEnd = false

    private fun TestScope.repository(fileName: String = "state.json", scope: CoroutineScope = backgroundScope) = LocalStateRepository(
        store = DataStoreFactory.create(
            serializer = StoredStateSerializer,
            scope = scope,
            produceFile = { folder.root.resolve(fileName) },
        ),
        clock = WallClock { now },
        deviceClass = object : DeviceClass {
            override val isLowEnd get() = lowEnd
        },
    )

    @Test
    fun creatingAHouseholdListsThisPhoneAsViewer() = runTest {
        val session = repository().create("Casa", "Pixel 3")

        val me = session.directory[session.self.id]!!
        assertEquals(NodeRole.VIEWER, me.role)
        assertEquals("Pixel 3", me.name)
        assertEquals(session.invite().household, session.household)
    }

    @Test
    fun announcingAsCameraMakesEveryAddressAnInviteSeedWifiFirst() = runTest {
        val repository = repository()
        repository.create("Casa", "Moto G")
        val tailnet = NodeEndpoint("100.64.0.7")
        val wifi = NodeEndpoint("192.168.77.163")

        repository.announceSelf(listOf(tailnet, wifi), NodeRole.CAMERA)

        assertEquals(listOf(wifi, tailnet), repository.currentSession()!!.invite().seeds)
    }

    @Test
    fun selfUpdatesWinEvenWhenTheClockGoesBackwards() = runTest {
        val repository = repository()
        val session = repository.create("Casa", "Moto G")
        val before = session.directory[session.self.id]!!.updatedAtMillis

        now = 10L
        repository.announceSelf(listOf(NodeEndpoint("100.64.0.7")), NodeRole.CAMERA)

        val after = repository.currentSession()!!.directory[session.self.id]!!
        assertTrue(after.updatedAtMillis > before)
        assertEquals(NodeRole.CAMERA, after.role)
    }

    @Test
    fun joiningAnotherHouseholdDropsTheOldDirectoryButKeepsIdentity() = runTest {
        val repository = repository()
        val first = repository.create("Casa", "Moto G")
        repository.announceSelf(listOf(NodeEndpoint("100.64.0.7")), NodeRole.CAMERA)
        val other = Household(HouseholdId("other"), "Oficina", HouseholdSecret.generate())

        val second = repository.join(Invite(other, emptyList()), "Moto G")

        assertEquals(first.self.id, second.self.id)
        assertNotEquals(first.household.id, second.household.id)
        assertEquals(listOf(first.self.id), second.directory.members.map { it.id })
    }

    @Test
    fun inviteSeedsSurviveUntilTheFirstSuccessfulExchange() = runTest {
        val repository = repository()
        val household = Household(HouseholdId("casa"), "Casa", HouseholdSecret.generate())
        val seed = NodeEndpoint("100.64.0.7")

        repository.join(Invite(household, listOf(seed)), "Moto G")
        assertEquals(listOf(seed), repository.currentSession()!!.bootstrapSeeds)

        repository.mergeDirectory(Directory())
        assertTrue(repository.currentSession()!!.bootstrapSeeds.isEmpty())
    }

    @Test
    fun becomingAMasterIsAnnouncedAndDuplicateAlertsAreDropped() = runTest {
        val repository = repository()
        val session = repository.create("Casa", "Samsung")

        repository.setReceiverEnabled(true)
        assertTrue(repository.currentSession()!!.directory[session.self.id]!!.receivesAlerts)

        val event = CameraEvent.new(NodeId("cam"), "Cocina", CameraEventType.MOVED, atMillis = 1)
        assertTrue(repository.record(event))
        assertFalse(repository.record(event))
        assertEquals(listOf(event), repository.alerts.first())
    }

    @Test
    fun announcingAddressesKeepsTheCurrentRole() = runTest {
        val repository = repository()
        val session = repository.create("Casa", "Moto G")
        repository.announceSelf(listOf(NodeEndpoint("192.168.1.5")), NodeRole.CAMERA)

        repository.announceSelf(listOf(NodeEndpoint("192.168.1.9")))

        val me = repository.currentSession()!!.directory[session.self.id]!!
        assertEquals(NodeRole.CAMERA, me.role)
        assertEquals(listOf(NodeEndpoint("192.168.1.9")), me.endpoints)
    }

    @Test
    fun renamingThisPhonePersistsItsNameAndPreservesItsCapabilities() = runTest {
        val repository = repository()
        val session = repository.create("Casa", "Samsung")
        val endpoint = NodeEndpoint("100.64.0.7")
        repository.announceSelf(listOf(endpoint), NodeRole.CAMERA)
        repository.setReceiverEnabled(true)

        repository.renameMember(session.self.id, "  Entrada  ")
        repository.announceSelf(listOf(endpoint), NodeRole.CAMERA)

        val updated = repository.currentSession()!!
        assertEquals("Entrada", updated.self.name)
        val member = updated.directory[session.self.id]!!
        assertEquals("Entrada", member.name)
        assertEquals(NodeRole.CAMERA, member.role)
        assertTrue(member.receivesAlerts)
        assertEquals(listOf(endpoint), member.endpoints)
        val persisted = folder.root.resolve("state.json").inputStream().use { StoredStateSerializer.readFrom(it) }
        assertEquals("Entrada", persisted.self?.name)
    }

    @Test
    fun remoteRenameConvergesAndSurvivesTheCamerasNextAnnouncement() = runTest {
        val viewer = repository("viewer.json")
        val viewerSession = viewer.create("Casa", "HONOR")
        val camera = repository("camera.json")
        val cameraSession = camera.join(Invite(viewerSession.household, emptyList()), "Samsung")
        camera.announceSelf(listOf(NodeEndpoint("100.64.0.7")), NodeRole.CAMERA)
        viewer.mergeDirectory(camera.currentSession()!!.directory)

        viewer.renameMember(cameraSession.self.id, "Patio")
        camera.mergeDirectory(viewer.currentSession()!!.directory)
        camera.announceSelf(listOf(NodeEndpoint("192.168.1.7")), NodeRole.CAMERA)
        viewer.mergeDirectory(camera.currentSession()!!.directory)

        assertEquals("Patio", camera.currentSession()!!.self.name)
        assertEquals("Patio", viewer.currentSession()!!.directory[cameraSession.self.id]?.name)
    }

    @Test
    fun blankNamesAreRejectedAndRemovedDevicesStayRemoved() = runTest {
        val repository = repository()
        val session = repository.create("Casa", "Samsung")

        assertTrue(runCatching { repository.renameMember(session.self.id, "  ") }.exceptionOrNull() is IllegalArgumentException)
        assertEquals("Samsung", repository.currentSession()!!.self.name)
        repository.removeMember(session.self.id)
        repository.renameMember(session.self.id, "Patio")
        assertTrue(repository.currentSession()!!.directory[session.self.id]!!.removed)
    }

    @Test
    fun oldPhonesStartWithCheaperDefaultsUntilTheUserChooses() = runTest {
        lowEnd = true
        val repository = repository()

        assertEquals(StreamProfile.LOW, repository.settings.first().profile)
        assertFalse(repository.liveGrid.first())

        repository.update { it.copy(profile = StreamProfile.HIGH) }
        repository.setLiveGrid(true)
        assertEquals(StreamProfile.HIGH, repository.settings.first().profile)
        assertTrue(repository.liveGrid.first())
    }

    @Test
    fun cameraLayoutsPersistWithoutChangingTheStreamOrPreviewPreference() = runTest {
        val repository = repository()
        repository.create("Casa", "HONOR")
        repository.setLiveGrid(false)
        val settings = repository.settings.first()
        assertEquals(CameraLayout.CARDS, repository.cameraLayout.first())

        for (layout in CameraLayout.entries) {
            repository.setCameraLayout(layout)

            assertEquals(layout, repository.cameraLayout.first())
            val persisted = folder.root.resolve("state.json").inputStream().use { StoredStateSerializer.readFrom(it) }
            assertEquals(layout, persisted.cameraLayout)
            assertEquals(settings, repository.settings.first())
            assertFalse(repository.liveGrid.first())
        }
    }

    @Test
    fun displayRotationCyclesPerCameraAndReturnsUpright() = runTest {
        val repository = repository()
        val garage = NodeId("garage")

        repeat(3) { repository.rotateDisplay(garage) }
        assertEquals(mapOf(garage to 270), repository.displayRotations.first())

        repository.rotateDisplay(garage)
        assertEquals(emptyMap<NodeId, Int>(), repository.displayRotations.first())
    }

    @Test
    fun creatorAdministersAndEveryoneElseJoinsAsACamera() = runTest {
        val owner = repository("owner.json")
        val camera = repository("camera.json")
        val created = owner.create("Casa", "HONOR")
        assertTrue(created.household.secured)
        assertEquals(AccessRole.ADMIN, created.role)

        val joined = camera.join(created.invite(), "A02")
        assertEquals(AccessRole.CAMERA, joined.role)

        owner.mergeDirectory(camera.currentSession()!!.directory)
        now += 1
        assertTrue(owner.assignRole(joined.self.id, AccessRole.VIEWER))
        camera.mergeDirectory(owner.currentSession()!!.directory)
        assertEquals(AccessRole.VIEWER, camera.currentSession()!!.role)

        assertFalse(camera.assignRole(created.self.id, AccessRole.REVOKED))
        assertEquals(AccessRole.ADMIN, owner.currentSession()!!.role)

        now += 1
        owner.removeMember(joined.self.id)
        camera.mergeDirectory(owner.currentSession()!!.directory)
        assertEquals(AccessRole.REVOKED, camera.currentSession()!!.role)
    }

    @Test
    fun appLockSurvivesRestartsAndKeepsCountingFailures() = runTest {
        val firstRun = CoroutineScope(coroutineContext + Job())
        val repository = repository(scope = firstRun)
        assertFalse(repository.enable("2400", AppDisguise.CLOCK))
        assertTrue(repository.enable("0730", AppDisguise.CLOCK))
        assertEquals(AppLockConfig(enabled = true, disguise = AppDisguise.CLOCK), repository.config.first())

        repeat(4) { repository.verify("0000") }
        firstRun.cancel()
        advanceUntilIdle()
        val reopened = repository()
        assertTrue(reopened.verify("1111") is PinCheck.LockedOut)
        now += 31_000
        assertEquals(PinCheck.Granted, reopened.verify("0730"))
        assertTrue("the clock has 3 icons", reopened.changeDisguise("0730", AppDisguise.CLOCK, iconStyle = 3) is PinCheck.Denied)
        assertEquals(PinCheck.Granted, reopened.changeDisguise("0730", AppDisguise.CALCULATOR, iconStyle = 2))
        assertEquals(AppLockConfig(enabled = true, disguise = AppDisguise.CALCULATOR, iconStyle = 2), reopened.config.first())
        assertTrue(reopened.disable("0000") is PinCheck.Denied)
        assertEquals(PinCheck.Granted, reopened.disable("0730"))
        assertFalse(reopened.config.first().enabled)
    }

    @Test
    fun removedMemberBecomesATombstoneAndLeaveForgetsTheHousehold() = runTest {
        val repository = repository()
        val session = repository.create("Casa", "Moto G")

        repository.removeMember(session.self.id)
        assertTrue(repository.currentSession()!!.directory[session.self.id]!!.removed)

        repository.leave()
        assertNull(repository.currentSession())
    }
}
