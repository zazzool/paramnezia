package net.paramnezia.deploy.cloud

/**
 * Providers the app can buy servers from. The catalog with sources and the providers left out
 * (and why) is `providers/README.md` in the repository; keep both in step.
 */
object CloudProviders {
    val all: List<ProviderInfo> = listOf(
        CloudzyProvider.INFO, TimewebProvider.INFO, VultrProvider.INFO, HetznerProvider.INFO, DigitalOceanProvider.INFO,
    )

    fun info(id: String): ProviderInfo? = all.firstOrNull { it.id == id }

    fun open(id: String, token: String): CloudProvider = when (id) {
        CloudzyProvider.INFO.id -> CloudzyProvider(token.trim())
        TimewebProvider.INFO.id -> TimewebProvider(token.trim())
        VultrProvider.INFO.id -> VultrProvider(token.trim())
        HetznerProvider.INFO.id -> HetznerProvider(token.trim())
        DigitalOceanProvider.INFO.id -> DigitalOceanProvider(token.trim())
        else -> throw CloudException("unknown_provider", "Неизвестный хостер $id")
    }

    /** At least this much memory: the installer builds AmneziaWG from source in Docker. */
    const val MIN_RAM_MB = 1000

    /**
     * The plan to suggest: the cheapest with at least 1 GB of RAM and 10 GB of disk. The installer
     * builds AmneziaWG from source in Docker, which does not fit in 512 MB.
     */
    fun suggest(plans: List<CloudPlan>): CloudPlan? =
        plans.filter { it.ramMb >= MIN_RAM_MB && (it.diskGb == 0 || it.diskGb >= 10) }.minByOrNull { it.monthly ?: Double.MAX_VALUE }
            ?: plans.minByOrNull { it.monthly ?: Double.MAX_VALUE }

    /**
     * Plans that look the same to a person (same CPU, memory, disk and price) shown once: catalogs
     * list the same offer under several product lines. The cheapest copy is kept, order is by price.
     */
    fun dedupe(plans: List<CloudPlan>): List<CloudPlan> =
        plans.sortedWith(compareBy({ it.monthly ?: Double.MAX_VALUE }, { it.cpu }, { it.ramMb }, { it.diskGb }))
            .distinctBy { listOf(it.cpu, it.ramMb, it.diskGb, it.monthly) }

    /** What to offer in one tap: the place and the plan, and why. */
    data class Pick(val region: CloudRegion, val plan: CloudPlan, val reason: String)

    /**
     * The one-tap choice: the region used last time if it still sells, otherwise the foreign region
     * closest to Russia (lowest ping, most stable routes) that has a plan big enough; within it,
     * [suggest]. Russian regions are never picked by themselves: they do not help with blocks.
     */
    fun recommend(catalog: CloudCatalog, lastRegion: String? = null): Pick? {
        val sellable = catalog.sellable
        fun fits(r: CloudRegion) = catalog.plansIn(r.id).any { it.ramMb >= MIN_RAM_MB }
        sellable.firstOrNull { it.id == lastRegion && fits(it) }?.let { r ->
            return Pick(r, suggest(catalog.plansIn(r.id))!!, "как в прошлый раз")
        }
        val foreign = sellable.filter { !it.inRussia }
        val best = foreign.filter(::fits).sortedWith(compareBy({ it.km ?: Int.MAX_VALUE }, { suggest(catalog.plansIn(it.id))?.monthly ?: Double.MAX_VALUE })).firstOrNull()
            ?: foreign.firstOrNull() ?: sellable.firstOrNull() ?: return null
        val plan = suggest(catalog.plansIn(best.id)) ?: return null
        val reason = when {
            best.inRussia -> "других мест нет"
            best.reach == Geo.Reach.NEAR -> "ближе всего к России"
            else -> "лучшее из доступных мест"
        }
        return Pick(best, plan, reason)
    }
}
