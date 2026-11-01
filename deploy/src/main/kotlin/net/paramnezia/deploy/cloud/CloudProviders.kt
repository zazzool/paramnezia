package net.paramnezia.deploy.cloud

/**
 * Providers the app can buy servers from. The catalog with sources and the providers left out
 * (and why) is `providers/README.md` in the repository; keep both in step.
 */
object CloudProviders {
    val all: List<ProviderInfo> = listOf(CloudzyProvider.INFO, TimewebProvider.INFO)

    fun info(id: String): ProviderInfo? = all.firstOrNull { it.id == id }

    fun open(id: String, token: String): CloudProvider = when (id) {
        CloudzyProvider.INFO.id -> CloudzyProvider(token.trim())
        TimewebProvider.INFO.id -> TimewebProvider(token.trim())
        else -> throw CloudException("unknown_provider", "Неизвестный хостер $id")
    }

    /**
     * The plan to suggest: the cheapest with at least 1 GB of RAM and 10 GB of disk. The installer
     * builds AmneziaWG from source in Docker, which does not fit in 512 MB.
     */
    fun suggest(plans: List<CloudPlan>): CloudPlan? =
        plans.filter { it.ramMb >= 1000 && (it.diskGb == 0 || it.diskGb >= 10) }.minByOrNull { it.monthly ?: Double.MAX_VALUE }
            ?: plans.minByOrNull { it.monthly ?: Double.MAX_VALUE }
}
