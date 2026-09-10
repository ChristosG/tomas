package gr.dimitris.app.core.difficulty

import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.ItemDao
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.ScheduleDao
import gr.dimitris.app.core.data.ScriptDao
import gr.dimitris.app.core.data.Speaker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first

/**
 * Where the five dots start on a phone that was already being used before they existed (spec §13).
 *
 * The dots arrived in phase 12 on a device his family had been practising with for months. Starting
 * everybody flat at [Difficulty.DEFAULT] would have told a man doing two-step word problems that he
 * was halfway down the ladder — and, since a sitting now runs at a level held inside the band
 * ([Difficulty.levelAtLoad]), it would have *taken the levels away*: level 14 clamped into band 2 is
 * level 4, and nothing on any screen would have said so. So the dot is read off what he was already
 * doing, once, per module, and after that it is his.
 *
 * Only what is really written counts as evidence. A phone installed this morning has no stored level
 * and no schedule rows, so every module lands on [Difficulty.DEFAULT] — which is the honest answer
 * for somebody the app has never met, and keeps a first run exactly as it was.
 *
 * Runs on the app's own scope at startup, after the seed importers, so the vocabulary and the
 * dialogues the two DB-backed derivations read are on the device. It is safe to run again: each
 * module is written at most once, guarded inside the [gr.dimitris.app.core.settings.Settings] edit.
 */
object DifficultyInit {

    /**
     * The two modules whose evidence is the **database** rather than the preference store: how long a
     * phrase he has been singing, and how many turns his dialogues ask of him. They are the ones
     * [watch] has to work out again when the database is replaced under the app.
     */
    internal val FROM_DATABASE = setOf(ModuleId.SINGSAY, ModuleId.SCRIPTS)

    /** Every module that has a row of dots, in Today order. The talk board has none. */
    suspend fun run(graph: AppGraph) {
        for (module in graph.modules.map { it.id }) {
            val needed = runCatching { graph.settings.difficultyNeedsInit(module).first() }
                .getOrElse { graph.errors.record("difficulty init $module", it); false }
            if (!needed) continue
            // A derivation that *threw* is not a derivation, and must not be marked done: one
            // transient Room failure at startup would otherwise pin «Τραγούδα και πες το» at dot 2
            // for the life of the install. Nothing is written, and the next launch tries again —
            // which is what already happens when the flag itself cannot be read.
            val derived = runCatching { derive(graph, module) }
                .getOrElse { graph.errors.record("difficulty derive $module", it); continue }
            runCatching { graph.settings.initialiseDifficulty(module, derived) }
                .onFailure { graph.errors.record("difficulty init write $module", it) }
        }
    }

    /**
     * And again, whenever the database is replaced underneath the app.
     *
     * [gr.dimitris.app.AppGraph.dbGeneration] is bumped by exactly the two events that move his
     * practice history without touching a single preference: a backup restored
     * ([gr.dimitris.app.AppGraph.reopenDatabase]) and a sync pull that brought rows down
     * (`onPulled`). Both are how a *second* phone gets his data, and on that phone [run] has already
     * been spent, at startup, on a database that held nothing but the seed.
     *
     * Only [FROM_DATABASE] is worked out again: the other four read a level or a target size out of
     * the preference store, which neither event changes. A dot a person has set is never revisited.
     *
     * Collects for the life of the process, on the graph's own scope. The first value is dropped
     * because it is the generation [run] has just been given.
     */
    suspend fun watch(graph: AppGraph) = watch(
        generations = graph.dbGeneration,
        forget = { modules ->
            runCatching { graph.settings.forgetDerivedDifficulty(modules) }
                .onFailure { graph.errors.record("difficulty reset", it) }
        },
        derive = { run(graph) },
    )

    /**
     * The rule on its own, so that "a database swap makes the two DB-backed dots be worked out again,
     * and the first generation is not a swap" is a thing a test can drive rather than a thing a device
     * has to be talked into. An [AppGraph] needs a `Context` and cannot be built on the JVM.
     */
    internal suspend fun watch(
        generations: Flow<Int>,
        forget: suspend (Set<ModuleId>) -> Unit,
        derive: suspend () -> Unit,
    ) {
        generations.drop(1).collect {
            forget(FROM_DATABASE)
            derive()
        }
    }

    /**
     * The dot this module's own progress already describes, or null to let
     * [gr.dimitris.app.core.settings.Settings.initialiseDifficulty] read it out of the store — which
     * is where «Αριθμοί», «Προτάσεις», «Γράψε» and «Δεξί χέρι» keep theirs.
     *
     * The two that are graded by *what they offer him* rather than by a level have to be read out of
     * the database instead, and both are read the same way: the hardest thing he has actually been
     * given. A schedule row is the proof that he was given it.
     */
    private suspend fun derive(graph: AppGraph, module: ModuleId): Int? = when (module) {
        ModuleId.SINGSAY -> singSayDot(graph.db.items(), graph.db.schedules())
        ModuleId.SCRIPTS -> scriptsDot(graph.db.scripts(), graph.db.schedules())
        else -> null
    }

    /**
     * «Τραγούδα και πες το» is graded by how long a phrase is, so the dot is the one that admits the
     * **longest phrase he has been practising**. Phrases he has never been given say nothing about
     * him; phrases he has are the length the module was already asking of him, and the ceiling has to
     * keep offering them or the upgrade would silently retire his own list.
     */
    internal suspend fun singSayDot(items: ItemDao, schedules: ScheduleDao): Int? {
        val practised = schedules.all(ModuleId.SINGSAY).map { it.itemId }.toSet()
        if (practised.isEmpty()) return null
        val longest = items.allActive()
            .filter { it.id in practised }
            .maxOfOrNull { Difficulty.syllablesOf(it.text) }
            ?: return null
        return Difficulty.syllableDot(longest)
    }

    /**
     * «Διάλογοι» is graded, until Task 6's `tier` column, by how many turns are his. Same rule: the
     * dot that admits the longest conversation he has been given. A dialogue nobody has opened yet is
     * not evidence about him.
     */
    internal suspend fun scriptsDot(scripts: ScriptDao, schedules: ScheduleDao): Int? {
        val practised = schedules.all(ModuleId.SCRIPTS).map { it.itemId }.toSet()
        if (practised.isEmpty()) return null
        val longest = scripts.activeScripts()
            .filter { it.id in practised }
            .maxOfOrNull { s -> scripts.linesFor(s.id).count { it.speaker == Speaker.DIMITRIS } }
            ?: return null
        return Difficulty.turnDot(longest)
    }
}
