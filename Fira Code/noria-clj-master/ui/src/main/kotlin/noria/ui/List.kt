package noria.ui

import noria.*
import noria.scene.*
import kotlin.math.max
import kotlin.math.min
import io.lacuna.bifurcan.Set

data class ItemsGroup<Item>(val items: List<Item>, val groupName: String?)
data class ListData<Item>(val groups: List<ItemsGroup<Item>>,
                          val selection: Set<Item>,
                          val itemKeyFn: (Item) -> Any,
                          val cursor: Item?,
                          val multiSelection: Boolean)

private fun <Item> nextItem(groups: List<ItemsGroup<Item>>,
                            currentItem: Item,
                            shouldCycle: Boolean,
                            itemKeyFn: (Item) -> Any,
                            forward: Boolean): Item {
  val flat = groups.flatMap { itemsGroup -> itemsGroup.items }
  val currentIdx = flat.map(itemKeyFn).indexOf(itemKeyFn(currentItem))
  val nextIdx = if (forward) currentIdx + 1 else currentIdx - 1
  return flat[if (shouldCycle) Math.floorMod(nextIdx, flat.size) else max(0, min(nextIdx, flat.size))]
}

private fun <Item> selectNextItem(list: ListData<Item>,
                                  shouldExpand: Boolean,
                                  shouldCycle: Boolean,
                                  forward: Boolean): ListData<Item> {
  return if (list.cursor != null) {
    val nextCursor = nextItem(list.groups, list.cursor, shouldCycle, list.itemKeyFn, forward)

    // todo shouldExpand also must remove elements from selection when moving in other direction
    val nextSelection = if (shouldExpand) list.selection.add(nextCursor) else Set.of(nextCursor)
    list.copy(cursor = nextCursor,
              selection = nextSelection)
  }
  else {
    val newCursor = list.groups.firstOrNull()?.items?.firstOrNull()
    if (newCursor != null) {
      val newSelection = Set.of(newCursor)
      list.copy(cursor = newCursor,
                selection = newSelection)
    }
    else {
      list
    }
  }
}

fun <Item> ListData<Item>.selectNextItem(shouldExpand: Boolean = false,
                                         shouldCycle: Boolean = false): ListData<Item> {
  return selectNextItem(this, shouldExpand, shouldCycle, true)
}

fun <Item> ListData<Item>.selectPrevItem(shouldExpand: Boolean = false,
                                         shouldCycle: Boolean = false): ListData<Item> {
  return selectNextItem(this, shouldExpand, shouldCycle, false)
}

fun <Item> ListData<Item>.setSelection(item: Item): ListData<Item> {
  return this.copy(cursor = item,
                   selection = Set.of(item))
}

fun <Item> ListData<Item>.withItems(newGroups: List<ItemsGroup<Item>>): ListData<Item> {
  if (this.groups == newGroups) return this

  val cursor = this.cursor
  val items = this.groups.flatMap { itemsGroup -> itemsGroup.items }
  val newItems = newGroups.flatMap { itemsGroup -> itemsGroup.items }
  val newKeys = Set.from(newItems.map(this.itemKeyFn))
  val cursorKey = if (cursor != null) this.itemKeyFn(cursor) else null

  val newCursor: Item?
  if (newKeys.contains(cursorKey) || cursor == null) {
    newCursor = cursor
  }
  else {
    val afterCursor = items.dropWhile { item -> this.itemKeyFn(item) != cursorKey }
    val beforeCursor = items.reversed().dropWhile { item -> this.itemKeyFn(item) != cursorKey }
    newCursor = afterCursor.zip(beforeCursor)
      .filter { (a, b) -> newKeys.contains(a) || newKeys.contains(b) }
      .map { (a, b) -> a ?: b }
      .firstOrNull()
  }

  val newSelection: Set<Item>
  if (this.multiSelection) {
    newSelection = Set.from(this.selection.filter { item -> newKeys.contains(this.itemKeyFn(item)) })
    if (newCursor != null) {
      newSelection.add(newCursor)
    }
  }
  else {
    newSelection = if (newCursor != null) Set.of(newCursor) else Set.of()
  }

  return this.copy(cursor = newCursor,
                   selection = newSelection,
                   groups = newGroups)
}

class ListItem<Item> private constructor(val isSeparator: Boolean,
                                         val separatorName: String?,
                                         val item: Item?) {
  companion object {
    fun <Item> separator(name: String?): ListItem<Item> {
      return ListItem(true, name, null)
    }

    fun <Item> item(item: Item): ListItem<Item> {
      return ListItem(false, null, item)
    }
  }
}

fun <Item> ListData<Item>.flatItems(): List<ListItem<Item>> {
  return this.groups.flatMap { group ->
    if (group.items.isNotEmpty()) {
      listOf(ListItem.separator(group.groupName),
             *group.items.map { item -> ListItem.item(item) }.toTypedArray())
    }
    else {
      emptyList()
    }
  }
}

fun <Item> renderListItem(renderItem: Frame.(Item) -> View,
                          renderSeparator: (String?) -> View): Frame.(ListItem<Item>) -> View {
  return { listItem ->
    if (listItem.isSeparator) {
      renderSeparator(listItem.separatorName)
    }
    else {
      renderItem(listItem.item!!)
    }
  }
}

fun <Item> listItemHeightKey(itemHeightKey: (Item) -> Any,
                             separatorHeightKey: (String?) -> Any): (ListItem<Item>) -> Any {
  return { listItem ->
    if (listItem.isSeparator) {
      separatorHeightKey(listItem.separatorName)
    }
    else {
      itemHeightKey(listItem.item!!)
    }
  }
}

fun <Item> listItemKey(itemKey: (Item) -> Any): (ListItem<Item>) -> Any {
  return { listItem ->
    if (listItem.isSeparator) {
      listItem.separatorName as Any
    }
    else {
      itemKey(listItem.item!!)
    }
  }
}

val NO_HEIGHT_GROUP = Object()
fun <Item> Frame.listView(items: List<Item>,
                          renderItem: Frame.(Item) -> View,
                          heightKey: (Item) -> Any = { _ -> NO_HEIGHT_GROUP },
                          itemKey: (Item) -> Any = { item -> item as Any }): View {
  return { cs ->
    val measureCache = mutableMapOf<Any, Size>()
    fun measure(item: Item): Size {
      val hk = heightKey(item)
      return if (hk == NO_HEIGHT_GROUP) {
        renderItem(item)(cs).size
      }
      else {
        measureCache.computeIfAbsent(hk) {
          renderItem(item)(cs).size
        }
      }
    }

    val height = items.fold(0f) { height, item ->
      height + measure(item).height
    }

    val size = Size(cs.maxWidth, height)
    val listLayoutNode = LayoutNode()
    Layout(size, listLayoutNode) { viewport ->
      renderBoundary(identity(items), renderItem, heightKey, itemKey, viewport) {
        var y = 0f
        for (item in items) {
          scope(key = itemKey(item)) {
            val itemSize = measure(item)
            val childBounds = Rect(Point(0f, y), itemSize)
            val childViewport = childViewport(childBounds, viewport)
            if (childViewport != null) {
              val itemView = renderItem(item)
              val itemLayout = itemView(cs)
              listLayoutNode.addChild(childBounds.origin(), itemLayout)
              mount(childBounds.origin()) {
                val itemRenderer = itemLayout.renderer
                itemRenderer(childViewport)
              }
            }
            y += itemSize.height
          }
        }
      }
    }
  }
}
