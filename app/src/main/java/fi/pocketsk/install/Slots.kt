package fi.pocketsk.install

/**
 * The two install slots, `files/sk/slots/A` and `B`, and which one an install goes to
 * (docs/PLAN.md §5.2). The live server runs from the slot `files/sk/current` points at; an
 * install always goes to the other one, so the live server is never touched until promotion
 * swaps the link, and the slot it ran from is still there to roll back to.
 *
 * Pure, so the choice is unit-tested; reading the link is [InstallManager]'s.
 */
internal object Slots {
    const val A = "A"
    const val B = "B"
    val ALL = listOf(A, B)

    /**
     * The slot a `current` link target names (`slots/B`, or an absolute path ending in it);
     * null for no link or anything that is not one of the two slots.
     */
    fun nameOf(target: String?): String? = target?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it in ALL }

    /** Where the next install goes: the slot that is not live, and A on a phone with none yet. */
    fun inactive(live: String?): String = if (live == A) B else A

    /** The link target promotion writes for [slot], relative so it survives a moved data directory. */
    fun linkTarget(slot: String): String {
        require(slot in ALL) { "not a slot: $slot" }
        return "slots/$slot"
    }
}
