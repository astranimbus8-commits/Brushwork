package com.brushwork.paint.exchange

/** Vector file formats Brushwork exports and imports (v1.5 §4.10, frozen). */
enum class VectorFormat(val extension: String, val mime: String) {
    SVG("svg", "image/svg+xml"),
    PDF("pdf", "application/pdf"),
}
