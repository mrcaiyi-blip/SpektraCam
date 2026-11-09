# 来源与许可

| 路径 | 来源 |
| --- | --- |
| `third_party/spektrafilm_android/engine` | 随 LutCam 已有集成一并提取的 C++ 胶片引擎。其上游是 Spektrafilm for Android 的 `15fb120996f03fc1e58a9a9d819e335f65d7a092`。保留源文件和上游哈希。 |
| `third_party/spektrafilm_android/patches` | LutCam 集成的本地补丁，包括直方图中灰、显影和 GPU 优化。构建时生成应用补丁后的文件。 |
| RAW 重建、胶片适配器、两端相机 UI | 从 LutCam 基线 `19f5f7253aa32ee284c29bc4bcab2794c57eb4cc` 的工作树提取。Android GLES 取景器取自 `96304eeaa5f867c78810e259aa7e56a6e56e7e47` 中的胶片取景实现。 |
| `assets/spektra/profiles`、`preview_luts` | 保留原胶片资料及经 SpektraFilm 烘焙的预览 LUT。配对与哈希见 `preview_luts/`，配置及衍生 LUT 的许可原文见 `licenses/spektrafilm/PROFILE_LICENSE.txt`。 |
| `assets/spektra/luts/spectral_upsampling`、`filters` | Spektrafilm 上游的光谱计算及印相滤镜数据。见上游资源说明和署名。 |
| `assets/spektra/samples` | Android 和 iOS 共用的 20 张胶片默认搭配示意，来自同一张 Google 数据集照片。照片与衍生示意使用 CC BY-SA 4.0。 |
| `assets/spektra/print_variants` | 用现有胶片、相纸与印片配置烘焙的 60 个额外搭配。目录含预览 LUT 和校验清单。LUT 与默认搭配使用相同的引擎烘焙与误差标准。 |
| `assets/spektra/film_samples` | 同一张照片经额外相纸搭配的预览 LUT 变换得到的 60 张两端共用的色彩示意。照片与衍生示意使用 CC BY-SA 4.0。 |

原样保留引擎的 NOTICE、GPLv3 正文、上游配置许可及原集成记录。NOTICE 中有关原上游编辑器的依赖说明描述的是它的发行内容。Android 使用系统 Vulkan，NDK C++ 运行库静态链接到 JNI 库。iOS 使用固定版本的 MoltenVK，单独适用其上游许可，副本随 iOS 应用提供。源码分发包含运行所需的构建脚本、补丁、GLSL、嵌入的 SPIR-V 及生成工具。

上游 NOTICE 与随原项目提供的 `PROFILE_LICENSE.txt` 对部分资源许可的描述不同。公开发布前，需核对固定版本每类素材对应的许可，分别记录胶片 profile、衍生 PLUT、光谱 LUT 与印相滤镜。发布人还需确认 LutCam 自有代码和修改的公开许可权利，并核对签名分发渠道的条款。

[Android 移植及署名](../third_party/spektrafilm_android/NOTICE.md) · [配置与衍生 LUT 许可](../licenses/spektrafilm/PROFILE_LICENSE.txt) · [上游资源说明](../third_party/spektrafilm_android/upstream_docs/ASSETS.md) · [MoltenVK 许可](https://github.com/KhronosGroup/MoltenVK/blob/v1.4.2/LICENSE)

## 示意照片

树林照片来自 Google HDR+ Burst Photography Dataset（Samuel W. Hasinoff、Dillon Sharlet、Ryan Geiss、Andrew Adams、Jonathan T. Barron、Florian Kainz、Jiawen Chen 和 Marc Levoy），场景 `4WBR_20150524_123748_247`，采用 2017-10-23 结果中的 `final.jpg`。项目将照片缩放至 640 × 475，应用各款胶片与相纸的预览 LUT 后编码为 JPEG。原图和这些衍生照片按 CC BY-SA 4.0 提供。[数据集](https://www.hdrplusdata.org/dataset.html) · [原图](https://storage.googleapis.com/hdrplusdata/20171106/results_20171023/4WBR_20150524_123748_247/final.jpg) · [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)

## 重新烘焙相纸搭配与示意照片

```sh
cmake -S . -B build/host -DCMAKE_BUILD_TYPE=Release
cmake --build build/host --target bake_preview_luts
build/host/tools/film_lut/native_tests/bake_preview_luts assets/spektra assets/spektra/print_variants --ios-print-variants
curl -fL -o /tmp/spektracam-forest.jpg https://storage.googleapis.com/hdrplusdata/20171106/results_20171023/4WBR_20150524_123748_247/final.jpg
uv run --with pillow --with numpy python tools/film_lut/bake_sample_cards.py --photo /tmp/spektracam-forest.jpg
python3 tools/film_lut/verify_preview_luts.py
```
