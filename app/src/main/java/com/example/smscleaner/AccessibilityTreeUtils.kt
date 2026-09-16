package com.example.smscleaner

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Depth-first walk of every descendant of [this], including itself. */
fun AccessibilityNodeInfo.selfAndDescendants(): Sequence<AccessibilityNodeInfo> = sequence {
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.addLast(this@selfAndDescendants)
    while (stack.isNotEmpty()) {
        val node = stack.removeLast()
        yield(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { stack.addLast(it) }
        }
    }
}

fun AccessibilityNodeInfo.findFirst(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
    selfAndDescendants().firstOrNull(predicate)

fun AccessibilityNodeInfo.findAll(predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> =
    selfAndDescendants().filter(predicate).toList()

fun AccessibilityNodeInfo.classNameContainsAny(fragments: List<String>): Boolean {
    val cn = className?.toString() ?: return false
    return fragments.any { cn.contains(it, ignoreCase = true) }
}

fun AccessibilityNodeInfo.textOrDesc(): String =
    (text?.toString() ?: "") + " " + (contentDescription?.toString() ?: "")

fun AccessibilityNodeInfo.matchesAnyText(candidates: List<String>): Boolean {
    val combined = textOrDesc().trim()
    return candidates.any { combined.equals(it, ignoreCase = true) || combined.contains(it, ignoreCase = true) }
}

fun AccessibilityNodeInfo.boundsInScreenRect(): Rect {
    val rect = Rect()
    getBoundsInScreen(rect)
    return rect
}

/** Prefers the real accessibility CLICK action; falls back to a tap gesture at the node's center. */
fun AccessibilityService.click(node: AccessibilityNodeInfo) {
    if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
    val clickableAncestor = findClickableAncestor(node)
    if (clickableAncestor != null && clickableAncestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
    tapGesture(node.boundsInScreenRect())
}

fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    var current: AccessibilityNodeInfo? = node.parent
    var hops = 0
    while (current != null && hops < 6) {
        if (current.isClickable) return current
        current = current.parent
        hops++
    }
    return null
}

/** Prefers ACTION_LONG_CLICK; falls back to a held-down gesture at the node's center. */
fun AccessibilityService.longClick(node: AccessibilityNodeInfo) {
    val supportsLongClick = node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK.id }
    if (supportsLongClick && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return
    longPressGesture(node.boundsInScreenRect())
}

/** Prefers ACTION_SCROLL_BACKWARD on the list node; falls back to an upward swipe gesture. */
fun AccessibilityService.scrollUp(listNode: AccessibilityNodeInfo) {
    val supportsScroll = listNode.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id }
    if (supportsScroll && listNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return
    swipeUpGesture(listNode.boundsInScreenRect())
}

private fun AccessibilityService.tapGesture(bounds: Rect) {
    val x = bounds.centerX().toFloat()
    val y = bounds.centerY().toFloat()
    val path = Path().apply { moveTo(x, y) }
    val stroke = GestureDescription.StrokeDescription(path, 0, 80)
    dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
}

private fun AccessibilityService.longPressGesture(bounds: Rect) {
    val x = bounds.centerX().toFloat()
    val y = bounds.centerY().toFloat()
    val path = Path().apply { moveTo(x, y) }
    // Duration comfortably past the OS long-press threshold (~500ms).
    val stroke = GestureDescription.StrokeDescription(path, 0, 700)
    dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
}

private fun AccessibilityService.swipeUpGesture(bounds: Rect) {
    // Swipe from ~80% down the list to ~20% down the list, i.e. finger moves up,
    // content scrolls further back in time (toward older messages).
    val x = bounds.centerX().toFloat()
    val startY = bounds.top + bounds.height() * 0.8f
    val endY = bounds.top + bounds.height() * 0.2f
    val path = Path().apply {
        moveTo(x, startY)
        lineTo(x, endY)
    }
    val stroke = GestureDescription.StrokeDescription(path, 0, 300)
    dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
}
