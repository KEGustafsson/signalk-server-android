package fi.pocketsk.install

/**
 * Which version the Dock's Updates card offers to install (docs/PLAN.md §8.1): the newest of
 * [available] (npm's versions, as [InstallManager.listAvailable] returns them) that is newer than
 * the live one and was never rolled back as bad by the health gate ([InstallHistory.isBad]), which
 * would only fail again. A live version that is not a version npm would name (a build from a
 * sideloaded tarball, say) gets the newest on offer. Null when there is nothing newer.
 *
 * Pure, so the rules are unit-tested.
 */
internal object UpdateOffer {
    fun pick(available: List<ServerVersion>, live: String?, history: InstallHistory): ServerVersion? {
        val current = live?.let { ServerVersion.parse(it) }
        return available
            .filter { current == null || it > current }
            .filterNot { history.isBad(it.text) }
            .maxOrNull()
    }
}
