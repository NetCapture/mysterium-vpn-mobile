package updated.mysterium.vpn.network.usecase

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import mysterium.GetProposalsRequest
import network.mysterium.service.core.NodeRepository
import network.mysterium.vpn.R
import updated.mysterium.vpn.database.entity.NodeEntity
import updated.mysterium.vpn.model.manual.connect.PresetFilter
import updated.mysterium.vpn.model.manual.connect.PriceLevel
import updated.mysterium.vpn.model.manual.connect.Proposal
import updated.mysterium.vpn.model.manual.connect.SystemPreset

class FilterUseCase(private val nodeRepository: NodeRepository) {

    private companion object {
        const val ALL_NODES_FILTER_ID = 0
        const val ALL_COUNTRY_CODE = "all_country"
        const val SERVICE_TYPE = "wireguard"
        val selectedResources = listOf(
            R.drawable.all_filters_selected,
            R.drawable.media_filters_selected,
            R.drawable.browsing_filters_selected,
            R.drawable.torrenting_filters_selected
        )
        val unselectedResources = listOf(
            R.drawable.all_filters_unselected,
            R.drawable.media_filters_unselected,
            R.drawable.browsing_filters_unselected,
            R.drawable.torrenting_filters_unselected
        )
    }

    suspend fun getSystemPresets(): List<PresetFilter> {
        val filterBytesArray = nodeRepository.getFilterPresets()
        val collectionType = object : TypeToken<Collection<SystemPreset?>?>() {}.type
        val systemFilters: List<SystemPreset> = Gson().fromJson(String(filterBytesArray), collectionType)
        val filters = emptyList<PresetFilter>().toMutableList()
        filters.add(PresetFilter(
            filterId = ALL_NODES_FILTER_ID,
            selectedResId = selectedResources[ALL_NODES_FILTER_ID],
            unselectedResId = unselectedResources[ALL_NODES_FILTER_ID],
            isSelected = true
        ))
        systemFilters.forEach { systemPreset ->
            filters.add(PresetFilter(
                filterId = systemPreset.filterId,
                selectedResId = selectedResources[systemPreset.filterId],
                unselectedResId = unselectedResources[systemPreset.filterId],
                title = systemPreset.title
            ))
        }
        return filters
    }

    suspend fun getProposalsByFilterId(filterId: Int): List<NodeEntity> {
        return if (filterId == ALL_NODES_FILTER_ID) {
            val proposalsRequest = GetProposalsRequest().apply {
                this.refresh = true
                includeFailed = true
                serviceType = SERVICE_TYPE
            }
            nodeRepository.proposals(proposalsRequest)
                .map { NodeEntity(it) }
        } else {
            nodeRepository.getProposalsByFilterId(filterId.toLong())
                .map { NodeEntity(it) }
        }
    }
}
