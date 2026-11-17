# 测试语料（assets/）

这个目录用来放**你自己的** EPUB。仓库不包含样书：验收用的那本是受版权保护的出版物，
不适合放进公开仓库。

- 把任意 `.epub` 放进本目录，`./gradlew build` 就会自动用它跑真实语料的测试
  （`SampleBooks` / `TestBooks` 会向上查找 `assets/*.epub`）。
- 也可以不改目录，直接指定路径：

  ```bash
  ./gradlew :core:test -Dlibepub.sample=/path/to/book.epub
  ```

- 目录为空时，依赖真实语料的测试会被 **skip**（不是失败），合成语料的测试照常运行，
  所以新克隆的仓库依然能构建通过。
