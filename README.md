# 🚗 RoadRadar

RoadRadar is a sophisticated Android application designed for real-time vehicle detection and speed estimation. By leveraging cutting-edge computer vision and machine learning, RoadRadar transforms your smartphone into a powerful traffic monitoring tool.


## 🌟 Key Features

- **Real-Time Detection**: Automatically identifies cars, trucks, and buses using high-performance object tracking.
- **Speed Estimation**: Dynamically calculates the speed of detected vehicles (in km/h) by analyzing frame-to-frame movement and perspective changes.
- **Smart Overlays**: Interactive bounding boxes and speed labels provide immediate visual feedback on the camera feed.
- **Precision Calibration**: Fine-tune the detection engine with adjustable parameters for camera height, vehicle width, and pixel-to-meter ratios.
- **Modern UI/UX**: Built entirely with Jetpack Compose using Material 3 design principles for a sleek, premium experience.

## 🛠️ Tech Stack

- **Languge**: [Kotlin](https://kotlinlang.org/)
- **UI Framework**: [Jetpack Compose](https://developer.android.com/jetpack/compose)
- **Camera API**: [CameraX](https://developer.android.com/training/camerax)
- **Machine Learning**: [Google ML Kit](https://developers.google.com/ml-kit) (Object Detection & Tracking)
- **Asynchrony**: [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) & [StateFlow](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/)

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug (or newer)
- Android Device running API 24 (Nougat) or higher
- Physical device recommended for camera performance

### Installation
1. Clone the repository:
   ```bash
   git clone https://github.com/yourusername/RoadRadar.git
   ```
2. Open the project in **Android Studio**.
3. Sync the project with Gradle files.
4. Run the application on your physical device.

## ⚙️ Calibration Guide

For the most accurate speed readings, use the **Calibration Settings** (cog icon) to adjust:
- **Pixel/Meter Ratio**: Adjust based on your camera's field of view.
- **Vehicle Width**: Set the average width of vehicles in your region.
- **Camera Height**: Inform the app about its physical placement relative to the road.

---
*RoadRadar — Eyes on the road, stats in your hand.*
