package io.snailrun.data.prefs

import io.snailrun.domain.coach.PairMode
import io.snailrun.domain.coach.Pairing
import io.snailrun.domain.coach.Partner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartnerCodecTest {

    private val partners = listOf(Partner(1, "Alex", 17.5), Partner(2, "Sam Doe", 14.0))
    private val pairings = mapOf(
        20_353L to Pairing(1, PairMode.Together),
        20_356L to Pairing(2, PairMode.Mirror),
    )

    @Test
    fun `partners survive the round trip`() {
        assertEquals(partners, PartnerCodec.decode(PartnerCodec.encode(partners)))
    }

    @Test
    fun `a name cannot break the string it is stored in`() {
        val odd = listOf(Partner(3, "A|B;C", 16.0))
        assertEquals(listOf(Partner(3, "A B C", 16.0)), PartnerCodec.decode(PartnerCodec.encode(odd)))
    }

    @Test
    fun `pairings survive the round trip`() {
        assertEquals(pairings, PairingCodec.decode(PairingCodec.encode(pairings)))
    }

    @Test
    fun `garbage reads as nothing rather than as a crash`() {
        assertTrue(PartnerCodec.decode("x;y").isEmpty())
        assertTrue(PairingCodec.decode("1:2:Sideways|nope").isEmpty())
        assertTrue(PartnerCodec.decode(null).isEmpty())
    }

    @Test
    fun `partners and pairings go into the backup and come back`() {
        val coach = CoachSettings(partners = partners, pairings = pairings)
        assertEquals(coach, coachSettingsFrom(coach.toBackupRows()))
    }

    @Test
    fun `a backup from before partners restores with none`() {
        val old = mapOf("nudge_off_pace" to "true", "baseline_asked" to "false")
        val coach = coachSettingsFrom(old)
        assertTrue(coach.partners.isEmpty())
        assertTrue(coach.pairings.isEmpty())
    }
}
