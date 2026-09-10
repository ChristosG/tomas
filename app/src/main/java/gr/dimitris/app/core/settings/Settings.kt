package gr.dimitris.app.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.difficulty.Difficulty
import gr.dimitris.app.modules.arcade.Adaptive
import gr.dimitris.app.modules.arcade.ArcadeGame
import gr.dimitris.app.modules.numbers.NumberProgression
import gr.dimitris.app.modules.sentences.SentenceTemplates
import gr.dimitris.app.modules.singsay.Key
import gr.dimitris.app.modules.singsay.Tempo
import gr.dimitris.app.modules.trace.TraceStrictness
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Whose phone this is. Asked once, on the very first launch, and changeable afterwards in the
 * caregiver settings.
 *
 * It decides where the app opens — Dimitris lands on «Δημήτρης» and a caregiver on «Φροντιστής» —
 * and nothing else. Sync itself does not care: his phone syncs too, or his practice would never
 * reach the people looking after him.
 */
enum class DeviceRole { DIMITRIS, CAREGIVER }

/** The answer to "whose phone is this?", including "nobody has been asked yet". */
@JvmInline
value class RolePick(val role: DeviceRole?) {
    val chosen: Boolean get() = role != null
    val effective: DeviceRole get() = role ?: DeviceRole.DIMITRIS
}

class Settings(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.settingsStore)

    val speechRate: Flow<Float> = store.data.map { it[SPEECH_RATE] ?: DEFAULT_RATE }
    suspend fun setSpeechRate(rate: Float) { store.edit { it[SPEECH_RATE] = rate.coerceIn(MIN_RATE, MAX_RATE) } }

    val caregiverLock: Flow<Boolean> = store.data.map { it[CAREGIVER_LOCK] ?: false }
    suspend fun setCaregiverLock(on: Boolean) { store.edit { it[CAREGIVER_LOCK] = on } }

    val seedVersion: Flow<Int> = store.data.map { it[SEED_VERSION] ?: 0 }
    suspend fun setSeedVersion(version: Int) { store.edit { it[SEED_VERSION] = version } }

    /** Its own counter: the dialogues and the vocabulary are bumped on different days. */
    val scriptsSeedVersion: Flow<Int> = store.data.map { it[SCRIPTS_SEED_VERSION] ?: 0 }
    suspend fun setScriptsSeedVersion(version: Int) { store.edit { it[SCRIPTS_SEED_VERSION] = version } }

    val sttEnabled: Flow<Boolean> = store.data.map { it[STT_ENABLED] ?: false }
    suspend fun setSttEnabled(on: Boolean) { store.edit { it[STT_ENABLED] = on } }

    /**
     * Where he is in the number-sense levels. The numbers module moves it, and the caregiver's
     * progress screen does; nothing else.
     *
     * Writing it also moves the dot to the band that owns the new level (see [followLevel]). The
     * dots and the level are two views of one thing since spec §13, and a level the module then
     * clamps back out of the band at load would be a caregiver's stepper that silently does nothing.
     */
    val numbersLevel: Flow<Int> = store.data.map { it[NUMBERS_LEVEL] ?: 1 }
    suspend fun setNumbersLevel(level: Int) {
        store.edit { p ->
            val n = level.coerceIn(NumberProgression.MIN_LEVEL, NumberProgression.MAX_LEVEL)
            p[NUMBERS_LEVEL] = n
            followLevel(p, ModuleId.NUMBERS, Difficulty.numbersDot(n))
        }
    }

    /**
     * How long a sentence he is building, 1..8 since phase 12: four levels of word order, and then
     * the articles, the prepositions, a clause and a question. See [numbersLevel] for why the dot
     * follows it.
     */
    val sentencesLevel: Flow<Int> = store.data.map { it[SENTENCES_LEVEL] ?: 1 }
    suspend fun setSentencesLevel(level: Int) {
        store.edit { p ->
            val n = level.coerceIn(SentenceTemplates.MIN_LEVEL, SentenceTemplates.MAX_LEVEL)
            p[SENTENCES_LEVEL] = n
            followLevel(p, ModuleId.SENTENCES, Difficulty.sentencesDot(n))
        }
    }

    /** What he is writing, 1..5: capitals, small letters, his name, words, words from memory. */
    val traceLevel: Flow<Int> = store.data.map { it[TRACE_LEVEL] ?: 1 }
    suspend fun setTraceLevel(level: Int) {
        store.edit { p ->
            val n = level.coerceIn(Difficulty.MIN, Difficulty.MAX)
            p[TRACE_LEVEL] = n
            followLevel(p, ModuleId.TRACE, Difficulty.traceDot(n))
        }
    }

    /**
     * The dot moved to wherever the level just written lives — inside the caregiver's bounds, and
     * **without** touching the level again: this is the level leading and the dot following, which is
     * the opposite direction from [setDifficulty].
     *
     * Where the bounds refuse the dot the level is pulled back to the band he is allowed, because a
     * fence that the progress screen can climb over is not a fence.
     */
    private fun followLevel(p: MutablePreferences, module: ModuleId, dot: Int) {
        val (floor, ceiling) = bounds(p, module)
        val allowed = Difficulty.clamp(dot, floor, ceiling)
        p[difficultyKey(module)] = allowed
        // A caregiver moving the stepper is a person deciding, exactly as his own tap is: the
        // migration must never derive over it. See [decidedKey].
        p[decidedKey(module)] = true
        if (allowed != dot) clampLevelIntoBand(p, module, allowed)
    }

    /**
     * Which hand he writes with, [HAND_LEFT] or [HAND_RIGHT]. It is the left one by default because
     * his right is the side the stroke took, and the writing screen says so out loud on every letter
     * — one line of certainty for a man who cannot ask which hand he is supposed to use.
     *
     * A caregiver sets it, and anything else that ever lands in the store reads as left rather than
     * as an empty hint.
     */
    val traceHand: Flow<String> = store.data.map { p -> p[TRACE_HAND]?.takeIf { it in HANDS } ?: HAND_LEFT }
    suspend fun setTraceHand(hand: String) { store.edit { it[TRACE_HAND] = if (hand in HANDS) hand else HAND_LEFT } }

    /**
     * How hard «Γράψε» marks him: «Χαλαρό», «Κανονικό» or «Αυστηρό». Κανονικό until a caregiver says
     * otherwise, and Κανονικό is the line the app is designed around — a hand-like trace of the right
     * letter passes it, and a wrong letter form does not, because a wrong movement learned is worse
     * than an exercise repeated.
     *
     * A name that is no longer one of the three — an old backup, a version that renamed them — reads
     * as [TraceStrictness.DEFAULT] rather than crashing a man out of the one module he can do alone.
     */
    val traceStrictness: Flow<TraceStrictness> = store.data.map { TraceStrictness.named(it[TRACE_STRICTNESS]) }
    suspend fun setTraceStrictness(level: TraceStrictness) { store.edit { it[TRACE_STRICTNESS] = level.name } }

    /**
     * How fast the sing-then-say melody moves. Κανονικός (=[Tempo.NORMAL]) until a caregiver slows
     * it down; a name that is no longer one of the two — an old backup, a version that renamed them
     * — reads as [Tempo.DEFAULT] rather than crashing him out of the module.
     */
    val melodyTempo: Flow<Tempo> = store.data.map { Tempo.named(it[MELODY_TEMPO]) }
    suspend fun setMelodyTempo(tempo: Tempo) { store.edit { it[MELODY_TEMPO] = tempo.name } }

    /**
     * Which key the sing-then-say melody sings in. Κανονικός (=[Key.NORMAL]) until a caregiver
     * drops it, for a voice — or a day — a lower register sits easier under.
     */
    val melodyKey: Flow<Key> = store.data.map { Key.named(it[MELODY_KEY]) }
    suspend fun setMelodyKey(key: Key) { store.edit { it[MELODY_KEY] = key.name } }

    /**
     * How big one arcade game's targets are, in dp. It is his difficulty and the arcade's whole
     * memory: every hit takes a little off it and every miss gives more back, and it is kept between
     * sittings so the size he worked down to is where he starts tomorrow.
     *
     * One key per game, because the four ask his hand for four different things. Pressing a circle
     * is the movement he keeps longest and pinching is the one he loses first, so a good round of
     * tapping used to drag the pinch game down to the floor with it — and then the photo he could
     * not open was the app's idea, not his hand's.
     *
     * Clamped on the way out as well as in, because a value from a restored backup — or from a
     * version that moved the range — must never leave him with a target too small to touch. A device
     * that stored the single pre-per-game size hands it to every game that has not been played since
     * ([LEGACY_ARCADE_TARGET_DP]): whatever he had worked down to is where each of them starts.
     */
    fun arcadeTargetDp(game: ArcadeGame): Flow<Float> = store.data.map { p ->
        Adaptive.clamp(p[arcadeKey(game)] ?: p[LEGACY_ARCADE_TARGET_DP] ?: Adaptive.START)
    }

    suspend fun setArcadeTargetDp(game: ArcadeGame, dp: Float) {
        store.edit { it[arcadeKey(game)] = Adaptive.clamp(dp) }
    }

    /**
     * How hard one module is, 1..5, as **he** set it on that module's first screen (spec §13).
     *
     * [Difficulty.DEFAULT] until he touches it, and always inside the caregiver's bounds — read as
     * well as written, because a floor raised while he was in a module must not leave the dots saying
     * one thing and the exercises doing another.
     *
     * One key per module ([difficultyKey]), like [arcadeTargetDp], because the six modules ask him
     * for six different things: a man who can build a four-word sentence may still be on single
     * letters in «Γράψε», and one shared number would drag each module to the level of whichever he
     * last adjusted.
     */
    fun difficulty(module: ModuleId): Flow<Int> = store.data.map { p -> readDifficulty(p, module) }

    /**
     * One tap on a dot. Clamped to the caregiver's bounds — a refused tap changes nothing, and the
     * row says why — and then the module's own level **jumps to the bottom of the new band**: a man
     * who has just asked for harder work meets the easiest of the harder work first.
     *
     * All of it in one [edit], so the difficulty and the level it implies can never be read apart.
     */
    suspend fun setDifficulty(module: ModuleId, n: Int) {
        store.edit { p ->
            val before = readDifficulty(p, module)
            val (floor, ceiling) = bounds(p, module)
            val wanted = Difficulty.clamp(n, floor, ceiling)
            p[difficultyKey(module)] = wanted
            // He has decided, so nothing may decide for him afterwards: the seed importers run before
            // the migration does, and a dot he taps in that window was otherwise overwritten by it a
            // second later. His own tap is the strongest evidence there is.
            p[decidedKey(module)] = true
            // Tapping the dot he is already on changes nothing — not even the level. He re-reads the
            // row more than once; a re-read must not cost him the level he has climbed to inside it.
            if (wanted != before) jumpToBandFloor(p, module, wanted)
        }
    }

    /**
     * The bounds the caregiver puts around the dots: he may set anything from [difficultyFloor] to
     * [difficultyCeiling], and the dots outside them are dimmed and refuse the tap.
     *
     * Wide open by default — 1 to 5 — because the point of the dots is that nobody has to ask
     * permission to try something harder. The bounds exist for the cases where that is not true: a
     * physiotherapist who does not want the arcade's targets under a certain size yet, a speech
     * therapist who wants this month's work to stay on two-word sentences.
     */
    fun difficultyFloor(module: ModuleId): Flow<Int> = store.data.map { p -> bounds(p, module).first }

    fun difficultyCeiling(module: ModuleId): Flow<Int> = store.data.map { p -> bounds(p, module).second }

    /**
     * Moves the lower bound, never above the upper one, and brings the stored value up with it if it
     * was below: a bound that is set and then silently disagrees with the dots he is looking at is
     * the one thing worse than no bound at all.
     */
    suspend fun setDifficultyFloor(module: ModuleId, n: Int) {
        store.edit { p ->
            val before = readDifficulty(p, module)
            val (_, ceiling) = bounds(p, module)
            p[floorKey(module)] = Difficulty.clamp(n).coerceAtMost(ceiling)
            reclamp(p, module, before)
        }
    }

    /** Moves the upper bound, never below the lower one, and brings the stored value down with it. */
    suspend fun setDifficultyCeiling(module: ModuleId, n: Int) {
        store.edit { p ->
            val before = readDifficulty(p, module)
            val (floor, _) = bounds(p, module)
            p[ceilingKey(module)] = Difficulty.clamp(n).coerceAtLeast(floor)
            reclamp(p, module, before)
        }
    }

    /** The pair, held to 1..5 and to each other, whatever is actually in the store. */
    private fun bounds(p: Preferences, module: ModuleId): Pair<Int, Int> {
        val floor = Difficulty.clamp(p[floorKey(module)] ?: Difficulty.MIN)
        val ceiling = Difficulty.clamp(p[ceilingKey(module)] ?: Difficulty.MAX).coerceAtLeast(floor)
        return floor to ceiling
    }

    private fun readDifficulty(p: Preferences, module: ModuleId): Int {
        val (floor, ceiling) = bounds(p, module)
        return Difficulty.clamp(p[difficultyKey(module)] ?: Difficulty.DEFAULT, floor, ceiling)
    }

    /**
     * A bound has moved. Where that moves the effective difficulty too, the module's level is
     * **clamped** into the new band — not reset to the bottom of it.
     *
     * The difference is somebody else's decision costing him work he did. He is on «Αριθμοί» dot 5,
     * level 14; the therapist caps him at 3 to keep this month on change-making. Band 3 is 5..7, so
     * 14 becomes 7 — the hardest level the new fence allows, which is the whole of what the fence
     * asked for. Resetting to 5 would take two more levels the therapist never asked him to give up,
     * with nothing on any screen to say so. A tap he makes himself ([setDifficulty]) still jumps to
     * the band's floor: choosing harder work and being handed the easiest of it is a kindness, being
     * *moved* and demoted is not.
     *
     * [before] is the effective value read *before* the bound was written, which is the only honest
     * comparison: a module he has never set reads as [Difficulty.DEFAULT], and a bound that leaves
     * that default where it was must not write a level he never asked to be moved to.
     */
    private fun reclamp(p: MutablePreferences, module: ModuleId, before: Int) {
        val after = readDifficulty(p, module)
        if (before == after) return
        p[difficultyKey(module)] = after
        clampLevelIntoBand(p, module, after)
    }

    /**
     * The module's own level, set to the easiest level of the band the dots now ask for. The three
     * modules that keep a level keep it here. «Λέξεις», «Τραγούδα και πες το» and «Διάλογοι» have no
     * level to move: their difficulty is which items the plan admits, and the next plan admits them.
     *
     * **«Δεξί χέρι» is not one of the three.** It has no level — it has four target sizes, and a
     * stored target size is a ceiling ([Difficulty.arcadeClamp]), never a starting point, whichever
     * door the change came through. Assigning the band's biggest size here was the one path that had
     * not followed that rule, and it undid the very thing the ceiling was written for: with the pinch
     * at 120 dp and the tap game at 50 dp, a tap on the *hardest* dot handed the tap game a 58 dp
     * circle back. He asked for harder work and one game got easier, with nothing said.
     */
    private fun jumpToBandFloor(p: MutablePreferences, module: ModuleId, difficulty: Int) {
        when (module) {
            ModuleId.NUMBERS -> p[NUMBERS_LEVEL] = Difficulty.numbers(difficulty).first
            ModuleId.SENTENCES -> p[SENTENCES_LEVEL] = Difficulty.sentences(difficulty).first
            ModuleId.TRACE -> p[TRACE_LEVEL] = Difficulty.trace(difficulty).first
            ModuleId.ARCADE -> clampLevelIntoBand(p, module, difficulty)
            else -> Unit
        }
    }

    /**
     * The module's own level held to the band the dots now allow, keeping everything inside it
     * exactly where it was. See [reclamp] for why a moved *bound* clamps where a moved *dot* jumps.
     *
     * «Δεξί χέρι» is a ceiling rather than a range ([Difficulty.arcadeClamp]): a target smaller than
     * the band is a hand doing better than it was asked to, and no fence should give that back.
     */
    private fun clampLevelIntoBand(p: MutablePreferences, module: ModuleId, difficulty: Int) {
        when (module) {
            ModuleId.NUMBERS ->
                p[NUMBERS_LEVEL] = (p[NUMBERS_LEVEL] ?: NumberProgression.MIN_LEVEL).coerceIn(Difficulty.numbers(difficulty))
            ModuleId.SENTENCES ->
                p[SENTENCES_LEVEL] = (p[SENTENCES_LEVEL] ?: SentenceTemplates.MIN_LEVEL).coerceIn(Difficulty.sentences(difficulty))
            ModuleId.TRACE ->
                p[TRACE_LEVEL] = (p[TRACE_LEVEL] ?: Difficulty.MIN).coerceIn(Difficulty.trace(difficulty))
            ModuleId.ARCADE -> ArcadeGame.entries.forEach { g ->
                p[arcadeKey(g)] = Difficulty.arcadeClamp(p[arcadeKey(g)] ?: Adaptive.START, difficulty)
            }
            else -> Unit
        }
    }

    /**
     * Whether this module's dot has ever been worked out for him, as opposed to defaulting to
     * [Difficulty.DEFAULT] because nobody had asked yet.
     *
     * The dots arrived in phase 12 on a phone that had already been in use for months. A flat "start
     * everybody at 2" would have told a man practising level 14 arithmetic that he was halfway down
     * the ladder, and — worse — the first thing that clamped his level into band 2 would have thrown
     * away everything he had climbed. [gr.dimitris.app.core.difficulty.DifficultyInit] therefore reads
     * his stored progress once, per module, and writes the dot that already describes him.
     */
    fun difficultyNeedsInit(module: ModuleId): Flow<Boolean> = store.data.map { !derivedAlready(it, module) }

    /** Derived once, or decided by a person: either ends the migration for that module. */
    private fun derivedAlready(p: Preferences, module: ModuleId): Boolean =
        p[initialisedKey(module)] == true || p[decidedKey(module)] == true

    /**
     * The one-time write. Sets the dot to [derived] — inside the caregiver's bounds — and marks the
     * module done, and it deliberately moves **no level**: the level is the evidence this value was
     * read off, so touching it would be the migration overwriting its own source.
     *
     * [derived] null means "work it out from what this store already holds" ([derivedFromStore]),
     * which is the answer for the four modules whose progress *is* a preference.
     *
     * [whenNothing] is where a module with nothing at all to read lands. It is [Difficulty.DEFAULT]
     * — "the app exactly as it was the day before" — for a phone that has been used, because a
     * module nobody has opened on a phone that *has* a history says nothing either way and the
     * upgrade must not quietly narrow «Λέξεις», whose dot is never derived from anything. On a phone
     * with no history at all the honest answer is [Difficulty.MIN], and the caller is the one that
     * can tell the two apart: [gr.dimitris.app.core.difficulty.DifficultyInit.run], which can also
     * see the database. See [noStoredProgress].
     *
     * Re-checked under the edit, so two callers racing at startup still write once.
     */
    suspend fun initialiseDifficulty(
        module: ModuleId,
        derived: Int? = null,
        whenNothing: Int = Difficulty.DEFAULT,
    ) {
        store.edit { p ->
            if (derivedAlready(p, module)) return@edit
            val (floor, ceiling) = bounds(p, module)
            val value = derived ?: derivedFromStore(p, module) ?: whenNothing
            p[difficultyKey(module)] = Difficulty.clamp(value, floor, ceiling)
            p[initialisedKey(module)] = true
        }
    }

    /**
     * True while no module's progress has been written here yet: nothing for [derivedFromStore] to
     * read, for any of them.
     *
     * Half of "has this phone ever been practised on". The other half is the database — the schedule
     * rows «Τραγούδα και πες το» and «Διάλογοι» are graded from — and only
     * [gr.dimitris.app.core.difficulty.DifficultyInit] can see both.
     */
    val noStoredProgress: Flow<Boolean> =
        store.data.map { p -> ModuleId.entries.none { derivedFromStore(p, it) != null } }

    /**
     * Forget that these modules' dots were ever derived, so the next
     * [gr.dimitris.app.core.difficulty.DifficultyInit] run works them out again.
     *
     * For the case where the *evidence moved*: two of the six derivations are read out of the
     * database — how long a phrase he has been singing, how many turns his dialogues ask of him — and
     * the flag that says "done" lives in DataStore, which a restore does not touch and sync does not
     * carry. The realistic second-device flow is install → seed → derive over an empty database →
     * **and then** his real vocabulary and his real Leitner rows arrive. Without this the two dots
     * were spent on a phone that knew nothing about him, and at [Difficulty.DEFAULT] the sing-then-say
     * ceiling is four syllables: every longer phrase that came with his data would sit out of the pool
     * with its schedule row overdue for ever, and the widen-back would not fire because the pool is
     * not empty.
     *
     * A dot a **person** has set is never forgotten ([decidedKey]): the database changing says nothing
     * about a decision he or a caregiver made.
     */
    suspend fun forgetDerivedDifficulty(modules: Set<ModuleId>) {
        store.edit { p -> modules.forEach { p.remove(initialisedKey(it)) } }
    }

    /**
     * Where the dots would already have been, read off the progress this store was keeping before
     * they existed. Null when there is nothing written to read — the *absence* of the key and not
     * its default value, which is the whole distinction: a phone that has never opened «Αριθμοί» has
     * no `numbers_level`, and a man who never climbed past level 1 has one that says 1.
     *
     * «Δεξί χέρι» is read off the **biggest** of the four stored target sizes rather than the
     * smallest. With [Difficulty.arcadeClamp] a ceiling, that is the one derivation under which no
     * game's target moves at all on the upgrade: everything smaller than the band is a hand doing
     * better than it was asked to, and it stands. Picking the smallest would have dragged the games
     * he finds hardest — the pinch, always — down to a target he has never managed.
     */
    private fun derivedFromStore(p: Preferences, module: ModuleId): Int? = when (module) {
        ModuleId.NUMBERS -> p[NUMBERS_LEVEL]?.let { Difficulty.numbersDot(it) }
        ModuleId.SENTENCES -> p[SENTENCES_LEVEL]?.let { Difficulty.sentencesDot(it) }
        ModuleId.TRACE -> p[TRACE_LEVEL]?.let { Difficulty.traceDot(it) }
        ModuleId.ARCADE ->
            (ArcadeGame.entries.mapNotNull { p[arcadeKey(it)] } + listOfNotNull(p[LEGACY_ARCADE_TARGET_DP]))
                .maxOrNull()?.let { Difficulty.arcadeDot(it) }
        else -> null
    }

    /**
     * Which Claude model the optional advisor asks. A caregiver never has to touch it; it is here
     * so a newer model can be tried on the phone without a new build, and so a model that stops
     * being served is one text field away from being fixed.
     *
     * Blank — or a value from a restored backup that is no longer a model id — reads as
     * [DEFAULT_CLAUDE_MODEL] rather than as an empty request the API would refuse.
     */
    val claudeModel: Flow<String> = store.data.map { p -> p[CLAUDE_MODEL]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_CLAUDE_MODEL }
    suspend fun setClaudeModel(model: String) {
        store.edit { p ->
            val trimmed = model.trim()
            if (trimmed.isEmpty()) p.remove(CLAUDE_MODEL) else p[CLAUDE_MODEL] = trimmed
        }
    }

    /**
     * Whether a turn he has just spoken is judged by Claude or by the phone (spec §13).
     *
     * Off until a caregiver deliberately turns it on, and that is the whole of the consent: with it
     * off — or with no key saved — [gr.dimitris.app.core.judge.TurnJudge] never touches the network
     * and every turn is matched locally, exactly as it was before phase 12.
     *
     * What goes up when it is on is one small JSON object of five fields — the kind of exercise, the
     * question, the target, what the recogniser heard, and the 1–5 difficulty — and the fixed system
     * prompt, which names him and states the three sentences of §1 that judging a sentence needs
     * ([gr.dimitris.app.core.judge.JudgeContract.SYSTEM_PROMPT]). Never the audio, never a photo,
     * never an item id, never a history, and nothing about his health beyond those three sentences.
     * The caregiver-facing line under the toggle says the short version of this, because a caregiver
     * consenting to "AI" has consented to nothing they can picture.
     */
    val claudeJudging: Flow<Boolean> = store.data.map { it[CLAUDE_JUDGING] ?: false }
    suspend fun setClaudeJudging(on: Boolean) { store.edit { it[CLAUDE_JUDGING] = on } }

    /**
     * Where the sync server lives, e.g. `https://sync.example.com`. Empty until a caregiver types
     * it in, and empty means the app never touches the network by itself.
     *
     * The token that goes with it is **not** here: it lives in
     * [gr.dimitris.app.core.secrets.SecretStore], encrypted, and never travels in a backup.
     */
    val syncUrl: Flow<String> = store.data.map { it[SYNC_URL]?.trim().orEmpty() }
    suspend fun setSyncUrl(url: String) {
        store.edit { p ->
            val trimmed = url.trim().trimEnd('/')
            if (trimmed.isEmpty()) p.remove(SYNC_URL) else p[SYNC_URL] = trimmed
        }
    }

    /** Null until the first-run question is answered; see [RolePick]. */
    val rolePick: Flow<RolePick> = store.data.map { p ->
        RolePick(p[DEVICE_ROLE]?.let { name -> runCatching { DeviceRole.valueOf(name) }.getOrNull() })
    }

    /** What the app should behave as. An unanswered question reads as Dimitris' phone. */
    val deviceRole: Flow<DeviceRole> = rolePick.map { it.effective }
    suspend fun setDeviceRole(role: DeviceRole) { store.edit { it[DEVICE_ROLE] = role.name } }

    /**
     * How far this phone has read the server's log: the highest sequence number it has taken in.
     * Advanced to the highest `seq` **received**, never to the number the server reports as its
     * own top — a capped page would otherwise leave rows behind that nothing would ever ask for
     * again.
     */
    val syncCursor: Flow<Long> = store.data.map { it[SYNC_CURSOR] ?: 0L }
    suspend fun setSyncCursor(seq: Long) { store.edit { it[SYNC_CURSOR] = seq.coerceAtLeast(0L) } }

    /** The `updatedAt` of the newest row this phone has pushed. Everything above it goes next time. */
    val syncPushedUpTo: Flow<Long> = store.data.map { it[SYNC_PUSHED_UP_TO] ?: 0L }
    suspend fun setSyncPushedUpTo(at: Long) { store.edit { it[SYNC_PUSHED_UP_TO] = at.coerceAtLeast(0L) } }

    /** When the last sync finished, for the one line the caregiver reads. 0 = never. */
    val lastSyncAt: Flow<Long> = store.data.map { it[LAST_SYNC_AT] ?: 0L }
    suspend fun setLastSyncAt(at: Long) { store.edit { it[LAST_SYNC_AT] = at } }

    /**
     * Both cursors back to the beginning. Called after a backup is restored: the database that
     * arrived is not the one the cursors were counted against, so the only honest position is the
     * start. It costs one long pull, and last-write-wins sorts out what comes back.
     */
    suspend fun resetSyncCursors() {
        store.edit { p ->
            p[SYNC_CURSOR] = 0L
            p[SYNC_PUSHED_UP_TO] = 0L
        }
    }

    /**
     * Which modules Dimitris gets. Everything is on unless a caregiver switched it off, except the
     * few in [DEFAULT_OFF], which are on only once someone deliberately asks for them.
     *
     * Two keys, because "never switched on" and "switched off" are different states: absence cannot
     * mean on for most modules and off for the extras at the same time.
     */
    val enabledModules: Flow<Set<ModuleId>> = store.data.map { p ->
        val off = p[DISABLED_MODULES].orEmpty()
        val extras = p[ENABLED_EXTRAS].orEmpty()
        ModuleId.entries.filter { it.name !in off && (it !in DEFAULT_OFF || it.name in extras) }.toSet()
    }
    suspend fun setModuleEnabled(id: ModuleId, on: Boolean) {
        store.edit { p ->
            if (id in DEFAULT_OFF) {
                val extras = p[ENABLED_EXTRAS].orEmpty().toMutableSet()
                if (on) extras.add(id.name) else extras.remove(id.name)
                p[ENABLED_EXTRAS] = extras
                // A module that *became* default-off may still be named in the disabled set, from a
                // day when switching it off was the only way to say so. [enabledModules] reads that
                // set first, so leaving the name there would make this switch dead: on, and still
                // not on the grid. Turning it on clears it; see [grandfatherNewlyDefaultOff].
                if (on) {
                    val off = p[DISABLED_MODULES].orEmpty()
                    if (id.name in off) p[DISABLED_MODULES] = off - id.name
                }
            } else {
                val off = p[DISABLED_MODULES].orEmpty().toMutableSet()
                if (on) off.remove(id.name) else off.add(id.name)
                p[DISABLED_MODULES] = off
            }
        }
    }

    /**
     * A module that has just **become** [DEFAULT_OFF] is off for new installs and stays on for the
     * phones that already had it. Written once per install, the first time this build runs.
     *
     * «Τραγούδα και πες το» joined [DEFAULT_OFF] in phase 12: it is for the long phrases that will
     * not come out yet, and Dimitris — who told us the app was too easy — should not have to meet a
     * singing exercise on the grid to find out he does not want it. But it has been on his phone
     * since phase 4, and a build that quietly took a tile off his Today screen because the default
     * moved would be the app deciding something for him that nobody asked it to decide. Hence
     * [NEWLY_DEFAULT_OFF]: on an install that already exists, those modules are written into
     * [ENABLED_EXTRAS] as though somebody had switched them on, which is exactly what having had
     * them means.
     *
     * A caregiver who had already switched one *off* keeps it off, and her switch keeps working. Her
     * "off" used to live in [DISABLED_MODULES], which [enabledModules] reads *first*; from today it
     * lives in the absence from [ENABLED_EXTRAS], which says the same thing. So the module is not
     * added to the extras — and its name is taken **out** of the disabled set, because a name left
     * there would make the switch dead: on, and still not on the grid.
     *
     * **"Already exists" is "this store has anything in it at all"**, so this must run before
     * anything else writes a preference: [gr.dimitris.app.DimitrisApp] calls it first, ahead of the
     * seed importers. There is no other honest signal — every key that says "this app has been used"
     * is written by something that also runs on a first launch.
     *
     * One key is excepted: `device_role`. It is written from the very first screen a brand-new
     * phone ever shows ([gr.dimitris.app.core.settings.RoleScreen]) on the UI thread, which is not
     * ordered against this pass — so on a first launch where that tap lands first and the process
     * then dies before the pass finishes, the next launch would read a non-empty store and
     * grandfather a phone that never had the module. Ignoring it removes the only write that can
     * plausibly get in front of the pass; every other key means the app was genuinely used.
     *
     * A backup restore does **not** carry this: [gr.dimitris.app.core.backup.Backup] replaces the
     * database and the media, never the preference store, so a replacement phone that installs the
     * app and imports his backup is a new install and loses the module he had. That is a caregiver's
     * one switch to put back, and it is in the handover's verify list.
     */
    suspend fun grandfatherNewlyDefaultOff() {
        store.edit { p ->
            if (p[DEFAULT_OFF_GENERATION] == MODULES_GENERATION) return@edit
            // Nothing written yet: a phone that met this app a moment ago, which gets the defaults
            // as they are today. Anything but the role is a phone that was here before.
            if (p.asMap().keys.any { it.name != DEVICE_ROLE.name }) {
                val names = NEWLY_DEFAULT_OFF.map { it.name }.toSet()
                // The ones a caregiver had already switched off are not switched back on — but the
                // name comes out of the disabled set, because from today that set is not where their
                // "off" lives. Absence from [ENABLED_EXTRAS] is, and it says the same thing.
                val off = p[DISABLED_MODULES].orEmpty()
                val extras = p[ENABLED_EXTRAS].orEmpty() + (names - off)
                p[ENABLED_EXTRAS] = extras
                if (off.any { it in names }) p[DISABLED_MODULES] = off - names
            }
            p[DEFAULT_OFF_GENERATION] = MODULES_GENERATION
        }
    }

    companion object {
        /**
         * Off until asked for: the arcade is a reward and not part of the daily work, and
         * sing-then-say is the answer to a phrase that will not come out at all — «για μεγάλες
         * φράσεις που δεν βγαίνουν ακόμα» (spec §13). A man who says most everyday words meets it
         * only when somebody decides he needs it.
         */
        val DEFAULT_OFF = setOf(ModuleId.ARCADE, ModuleId.SINGSAY)

        /**
         * The ones that joined [DEFAULT_OFF] after phones were already using them: what
         * [grandfatherNewlyDefaultOff] switches back on for an install that already existed. Paired
         * with [MODULES_GENERATION] so that a later phase adding another one runs its own pass
         * rather than this one's a second time — and so that a module listed here may safely be
         * removed from the list once every phone in the family has had the pass.
         */
        val NEWLY_DEFAULT_OFF = setOf(ModuleId.SINGSAY)

        /** Bumped whenever [NEWLY_DEFAULT_OFF] changes. 12 is the phase that first needed it. */
        const val MODULES_GENERATION = 12

        const val HAND_LEFT = "LEFT"
        const val HAND_RIGHT = "RIGHT"
        val HANDS = setOf(HAND_LEFT, HAND_RIGHT)

        /** The advisor's model unless a caregiver types another one. */
        const val DEFAULT_CLAUDE_MODEL = "claude-opus-5"

        const val DEFAULT_RATE = 0.8f
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 1.3f
        private val SPEECH_RATE = floatPreferencesKey("speech_rate")
        private val CAREGIVER_LOCK = booleanPreferencesKey("caregiver_lock")
        private val SEED_VERSION = intPreferencesKey("seed_version")
        private val SCRIPTS_SEED_VERSION = intPreferencesKey("scripts_seed_version")
        private val STT_ENABLED = booleanPreferencesKey("stt_enabled")
        private val NUMBERS_LEVEL = intPreferencesKey("numbers_level")
        private val SENTENCES_LEVEL = intPreferencesKey("sentences_level")
        private val TRACE_LEVEL = intPreferencesKey("trace_level")
        private val TRACE_HAND = stringPreferencesKey("trace_hand")
        private val TRACE_STRICTNESS = stringPreferencesKey("trace_strictness")
        private val MELODY_TEMPO = stringPreferencesKey("melody_tempo")
        private val MELODY_KEY = stringPreferencesKey("melody_key")
        private val CLAUDE_MODEL = stringPreferencesKey("claude_model")
        private val CLAUDE_JUDGING = booleanPreferencesKey("claude_judging")
        private val SYNC_URL = stringPreferencesKey("sync_url")
        private val DEVICE_ROLE = stringPreferencesKey("device_role")
        private val SYNC_CURSOR = longPreferencesKey("sync_cursor")
        private val SYNC_PUSHED_UP_TO = longPreferencesKey("sync_pushed_up_to")
        private val LAST_SYNC_AT = longPreferencesKey("last_sync_at")

        /** One target size per game: `arcade_target_dp_tap` and its three siblings. */
        private fun arcadeKey(game: ArcadeGame) = floatPreferencesKey("arcade_target_dp_${game.id}")

        /**
         * One difficulty per module — `difficulty_NUMBERS` and its siblings — and one pair of bounds
         * around it. Keyed by the enum *name* rather than by its ordinal, so reordering [ModuleId]
         * can never hand «Γράψε» the setting a caregiver chose for «Δεξί χέρι».
         */
        private fun difficultyKey(module: ModuleId) = intPreferencesKey("difficulty_${module.name}")
        private fun floorKey(module: ModuleId) = intPreferencesKey("difficulty_floor_${module.name}")
        private fun ceilingKey(module: ModuleId) = intPreferencesKey("difficulty_ceiling_${module.name}")

        /** Set once, when this module's dot has been derived from what he was already doing. */
        private fun initialisedKey(module: ModuleId) = booleanPreferencesKey("difficulty_ready_${module.name}")

        /**
         * Set when this module's dot has been **settled by something better than a guess**: his own
         * tap ([setDifficulty]), or a level write — a caregiver's stepper, a sitting's progression,
         * a module healing its own clamp — which [followLevel] turns into the dot that owns it.
         *
         * Its own key, separate from [initialisedKey], and never cleared. [forgetDerivedDifficulty]
         * exists so the migration runs again when the database changes underneath it, and neither a
         * restore nor a sync says anything about a choice somebody made or a level he earned.
         */
        private fun decidedKey(module: ModuleId) = booleanPreferencesKey("difficulty_set_${module.name}")

        /**
         * The one size the arcade had before each game kept its own. Read as the starting point for
         * a game that has not been played since, never written again.
         */
        private val LEGACY_ARCADE_TARGET_DP = floatPreferencesKey("arcade_target_dp")
        private val DISABLED_MODULES = stringSetPreferencesKey("disabled_modules")

        /** The [DEFAULT_OFF] ones someone has switched on. Meaningless for every other module. */
        private val ENABLED_EXTRAS = stringSetPreferencesKey("enabled_extras")

        /** Which [NEWLY_DEFAULT_OFF] pass this install has had. See [grandfatherNewlyDefaultOff]. */
        private val DEFAULT_OFF_GENERATION = intPreferencesKey("default_off_generation")
    }
}
