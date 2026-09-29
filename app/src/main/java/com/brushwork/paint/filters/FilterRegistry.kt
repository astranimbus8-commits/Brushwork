package com.brushwork.paint.filters

import com.brushwork.paint.filters.adjust.adjustFilters
import com.brushwork.paint.filters.art.artFilters
import com.brushwork.paint.filters.blur.blurFilters
import com.brushwork.paint.filters.distort.distortFilters
import com.brushwork.paint.filters.draw.drawFilters
import com.brushwork.paint.filters.pixelate.pixelateFilters
import com.brushwork.paint.filters.style.styleFilters

/** Every filter in the app. Each category package exposes one `val xxxFilters: List<Filter>`. */
object FilterRegistry {
    val all: List<Filter> by lazy {
        val list = adjustFilters + blurFilters + styleFilters + drawFilters + artFilters + pixelateFilters + distortFilters
        val dup = list.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(dup.isEmpty()) { "Duplicate filter ids: $dup" }
        list
    }

    fun byId(id: String): Filter? = all.firstOrNull { it.id == id }

    fun byCategory(): Map<FilterCategory, List<Filter>> = all.groupBy { it.category }.toSortedMap(compareBy { it.ordinal })
}
