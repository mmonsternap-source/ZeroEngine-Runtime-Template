package com.zero.engine.core

open class Node2D(val name: String) {
    var x: Float = 0f
    var y: Float = 0f
    var rotation: Float = 0f
    var scaleX: Float = 1f
    var scaleY: Float = 1f
    var visible: Boolean = true

    val globalX: Float get() = (parent?.globalX ?: 0f) + x
    val globalY: Float get() = (parent?.globalY ?: 0f) + y

    var parent: Node2D? = null
        private set
    val children = mutableListOf<Node2D>()

    fun addChild(child: Node2D) {
        child.parent = this
        children.add(child)
        child.onReady()
    }

    fun removeChild(child: Node2D) {
        child.parent = null
        children.remove(child)
    }

    open fun onReady() {}
    open fun onProcess(delta: Float) {}
    open fun onDraw(renderer: Any) {}

    internal fun updateTree(delta: Float) {
        onProcess(delta)
        for (i in children.indices) {
            children[i].updateTree(delta)
        }
    }

    internal fun drawTree(renderer: Any) {
        if (!visible) return
        onDraw(renderer)
        for (i in children.indices) {
            children[i].drawTree(renderer)
        }
    }
}
