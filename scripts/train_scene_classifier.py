"""
SPECTRA Scene Classifier Training Script
Trains MobileNetV3-Large on scene classification for 11 categories.

Usage:
    pip install tensorflow tensorflow-datasets pillow
    python train_scene_classifier.py --data_dir ./scene_data --output ./scene_classifier.tflite

Data directory structure:
    scene_data/
        LANDSCAPE/   (200+ images)
        PORTRAIT/    (200+ images)
        FOOD/        (200+ images)
        NIGHT/       (200+ images)
        ARCHITECTURE/(200+ images)
        MACRO/       (200+ images)
        PET/         (200+ images)
        ACTION/      (200+ images)
        DOCUMENT/    (200+ images)
        INDOOR/      (200+ images)
        UNKNOWN/     (200+ images)

Suggested data sources:
    - Places365-Standard: field/mountain/coast -> LANDSCAPE, kitchen/bedroom -> INDOOR
    - Food-101: all categories -> FOOD
    - Oxford-IIIT Pet: all categories -> PET
    - Open Images V7: sports -> ACTION, letter/document -> DOCUMENT
    - Manual collection: close-up flowers/insects -> MACRO, bridges/buildings -> ARCHITECTURE
"""

import argparse
import os
import sys

import tensorflow as tf
from tensorflow.keras import layers, Model
from tensorflow.keras.applications import MobileNetV3Large
from tensorflow.keras.preprocessing.image import ImageDataGenerator

SCENE_LABELS = [
    "LANDSCAPE", "PORTRAIT", "FOOD", "NIGHT", "ARCHITECTURE",
    "MACRO", "PET", "ACTION", "DOCUMENT", "INDOOR", "UNKNOWN"
]
INPUT_SIZE = 224
BATCH_SIZE = 32


def create_model(num_classes: int) -> Model:
    base = MobileNetV3Large(
        input_shape=(INPUT_SIZE, INPUT_SIZE, 3),
        include_top=False,
        weights="imagenet",
        pooling="avg"
    )
    base.trainable = False

    inputs = tf.keras.Input(shape=(INPUT_SIZE, INPUT_SIZE, 3))
    x = tf.keras.applications.mobilenet_v3.preprocess_input(inputs)
    x = base(x, training=False)
    x = layers.Dropout(0.2)(x)
    outputs = layers.Dense(num_classes, activation="softmax")(x)

    return Model(inputs, outputs)


def train(data_dir: str, output_path: str, epochs_frozen: int = 20, epochs_finetune: int = 10):
    datagen_train = ImageDataGenerator(
        rescale=1.0 / 255,
        rotation_range=15,
        width_shift_range=0.1,
        height_shift_range=0.1,
        horizontal_flip=True,
        validation_split=0.15
    )
    datagen_val = ImageDataGenerator(rescale=1.0 / 255, validation_split=0.15)

    train_gen = datagen_train.flow_from_directory(
        data_dir,
        target_size=(INPUT_SIZE, INPUT_SIZE),
        batch_size=BATCH_SIZE,
        class_mode="categorical",
        classes=SCENE_LABELS,
        subset="training"
    )
    val_gen = datagen_val.flow_from_directory(
        data_dir,
        target_size=(INPUT_SIZE, INPUT_SIZE),
        batch_size=BATCH_SIZE,
        class_mode="categorical",
        classes=SCENE_LABELS,
        subset="validation"
    )

    model = create_model(len(SCENE_LABELS))

    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=1e-4),
        loss="categorical_crossentropy",
        metrics=["accuracy"]
    )

    print(f"Phase 1: Training classifier head ({epochs_frozen} epochs)...")
    model.fit(train_gen, validation_data=val_gen, epochs=epochs_frozen)

    base_model = model.layers[2]
    for layer in base_model.layers[-30:]:
        layer.trainable = True

    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=1e-5),
        loss="categorical_crossentropy",
        metrics=["accuracy"]
    )

    print(f"Phase 2: Fine-tuning last blocks ({epochs_finetune} epochs)...")
    model.fit(train_gen, validation_data=val_gen, epochs=epochs_finetune)

    val_loss, val_acc = model.evaluate(val_gen)
    print(f"Validation accuracy: {val_acc:.4f}")

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    tflite_model = converter.convert()

    with open(output_path, "wb") as f:
        f.write(tflite_model)

    size_mb = os.path.getsize(output_path) / (1024 * 1024)
    print(f"Model saved to {output_path} ({size_mb:.1f} MB)")
    print(f"Labels order: {SCENE_LABELS}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Train SPECTRA scene classifier")
    parser.add_argument("--data_dir", required=True, help="Path to scene data directory")
    parser.add_argument("--output", default="scene_classifier.tflite", help="Output TFLite path")
    parser.add_argument("--epochs_frozen", type=int, default=20)
    parser.add_argument("--epochs_finetune", type=int, default=10)
    args = parser.parse_args()

    if not os.path.isdir(args.data_dir):
        print(f"Error: {args.data_dir} is not a directory")
        sys.exit(1)

    train(args.data_dir, args.output, args.epochs_frozen, args.epochs_finetune)
