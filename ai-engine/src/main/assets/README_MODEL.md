# Scene Classifier TFLite Model

## Required model: `scene_classifier.tflite`

This file is NOT included in the repository due to its size (~16 MB).
The app gracefully falls back to heuristic scene classification when the model is absent.

## Model specification

| Property       | Value                                         |
|----------------|-----------------------------------------------|
| Architecture   | MobileNetV3-Large                             |
| Dataset        | Places365-Standard                            |
| Input shape    | `[1, 224, 224, 3]` float32 (RGB, 0.0-1.0)    |
| Output shape   | `[1, 365]` float32 (softmax over 365 classes) |
| Quantization   | Dynamic-range (uint8 weights, float activations) recommended for on-device speed |

## Label mapping

The 365 output indices correspond to the Places365 categories in alphabetical order.
`scene_labels.txt` (in this same assets directory) maps each index to one of SPECTRA's
`SceneType` enum values: LANDSCAPE, PORTRAIT, FOOD, NIGHT, ARCHITECTURE, MACRO, PET,
ACTION, DOCUMENT, INDOOR, or UNKNOWN.

## How to obtain / convert the model

1. Download the pre-trained MobileNetV3-Large Places365 checkpoint from the
   official Places365 repository or TensorFlow Hub.
2. Convert to TFLite:
   ```python
   import tensorflow as tf
   converter = tf.lite.TFLiteConverter.from_saved_model("saved_model_dir")
   converter.optimizations = [tf.lite.Optimize.DEFAULT]
   tflite_model = converter.convert()
   with open("scene_classifier.tflite", "wb") as f:
       f.write(tflite_model)
   ```
3. Place the resulting `scene_classifier.tflite` in this directory
   (`ai-engine/src/main/assets/`).
