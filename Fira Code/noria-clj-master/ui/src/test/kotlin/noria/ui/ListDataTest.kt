package noria.ui

import io.lacuna.bifurcan.Set
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class ListDataTest {

  fun <Item> makeSingleGroup(vararg items: Item): List<ItemsGroup<Item>> {
    return listOf(ItemsGroup(items.asList(), "SampleGroup"))
  }

  fun <Item> makeSingleGroupList(vararg items: Item): ListData<Item> {
    return ListData(makeSingleGroup(*items),
                    Set(),
                    { i: Item -> i as Any },
                    null,
                    false)
  }

  @Test
  fun cursorTest1() {
    val list = makeSingleGroupList(1)
    Assertions.assertEquals(null, list.cursor)
    val list1 = list.selectNextItem()
    Assertions.assertEquals(1, list1.cursor)
  }

  @Test
  fun cursorTest2() {
    val list = makeSingleGroupList(1, 2, 3)
    Assertions.assertEquals(null, list.cursor)
    val list1 = list.selectNextItem()
    Assertions.assertEquals(1, list1.cursor)
    val list2 = list1.selectNextItem()
    Assertions.assertEquals(2, list2.cursor)
    val list3 = list2.selectNextItem()
    Assertions.assertEquals(3, list3.cursor)
  }

  @Test
  fun cursorTest3() {
    val list = makeSingleGroupList(1, 2, 3)
    Assertions.assertEquals(null, list.cursor)
    val list1 = list.selectPrevItem()
    Assertions.assertEquals(1, list1.cursor)
    val list2 = list1.selectPrevItem(shouldCycle = true).selectPrevItem(shouldCycle = true)
    Assertions.assertEquals(2, list2.cursor)
  }

  @Test
  fun withItemsTest1() {
    val list = makeSingleGroupList(1, 2, 3)
    val list1 = list.withItems(makeSingleGroup(4, 5, 6))
    Assertions.assertEquals(null, list1.cursor)
  }

  @Test
  fun withItemsTest2() {
    val list = makeSingleGroupList(1, 2, 3).selectNextItem()
    val list1 = list.withItems(makeSingleGroup(2, 1, 3))
    Assertions.assertEquals(1, list1.cursor)
  }

  @Test
  fun withItemsTest3() {
    val list = makeSingleGroupList(1, 2, 3).selectNextItem().selectNextItem()
    Assertions.assertEquals(2, list.cursor)
    val list1 = list.withItems(makeSingleGroup(1, 4, 3))
    Assertions.assertEquals(3, list1.cursor)
  }

}