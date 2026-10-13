# D 款 · 弧线韵律

用户已确认使用此款：深蓝底、白色 T、青色 V，圆润连字内嵌播放三角。

![已确认的 D 款](icon-tv2-d-approved.png)

`icon-tv2-d-approved.png` 是内置 imagegen 工具生成并经用户确认的原图，尺寸 1254 × 1254；没有重新绘制字形。SHA-256：`D3430D4F541F032D366AA247D5BFBFE59D2FEA96CB455EC5C3328CC6D58CB753`。

## Android 资源

- `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`：48、72、96、144、192 像素。
- `drawable-nodpi/ic_launcher_artwork.png`：1080 像素自适应图层，原图等比缩放到 800 像素并四边各补 140 像素深蓝。图案保留在中心安全范围，圆形、圆角方形遮罩不会切断字母。
- `mipmap-anydpi-v26/ic_launcher.xml`：颜色背景和位图前景；前景保留已确认图的深蓝底，避免重新抠图改变边缘。
- `drawable-xhdpi/tv_banner.png`：320 × 180 电视启动横幅，只裁掉原图上下背景，完整保留 TV 图案。
- 菜单页眉通过 `ApplicationInfo.loadIcon()` 使用同一应用图标。

资源已纳入版本控制，正常 Android 构建不需要图像工具。只有重新导出尺寸时需要 Node.js 和可解析到的 `sharp` 包，运行 `node scripts/generate-icons.cjs`。脚本仅执行缩放、补边和横幅裁切。

自适应图标尺寸依据 [Android 官方说明](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。本版没有另行设计单色主题图标。

## 生成记录

方式：内置 imagegen；TV2 原设计作为风格参考；透明背景关闭。以下为 D 款完整提示词，已确认原图以本目录 PNG 为准：

```text
Use case: logo-brand. Create ONE square app icon design preview for WebView television. The input image is a STYLE AND IDENTITY REFERENCE: retain the recognizable uppercase TV monogram, ivory white T, vivid cyan V, a right-facing PLAY triangle incorporated as negative space, and deep midnight navy full-bleed square background. Reimagine the letter construction into an artistic, professionally art-directed graphic identity. TV must remain readable, optically balanced, and strong at small app-icon sizes. Use generous clear space, with the complete symbol within the middle 72% of the square. Precise vector-like contours, restrained palette, minimal flat graphic design. No captions, extra letters, border, watermark, device mockup, antenna, television stand, background decorations, glow, bevel, photographic texture, or realistic 3D. Only the artistic TV mark on navy.

Direction D — SOFT MODERNIST COUNTERPOINT. Create a sophisticated rounded geometric TV ligature: a strong ivory T with an extended gently arched crossbar, paired with a cyan V whose two broad arms converge into a gracefully curved bottom vertex. Make the T's right edge and V's left arm nest into one another using a clean curving seam; playfully offset the heights slightly for expressive rhythm while remaining optically balanced. Embed one small, beautifully proportioned navy right-facing play triangle in the broad cyan stroke near the junction. Inspired by the general principles of mid-century modernist graphic composition: arcs against diagonals, warmth with precision. More organic and compact than the reference, still unmistakably TV; solid flat colors.
```
