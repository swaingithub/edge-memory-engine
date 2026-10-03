#!/usr/bin/env python3
"""
Converts BAAI/bge-small-en-v1.5 to an Apple Core ML model (.mlpackage)
optimized for the Apple Neural Engine (ANE).
"""

import os
import torch
import coremltools as ct
from transformers import AutoModel, AutoTokenizer

MODEL_ID = "BAAI/bge-small-en-v1.5"
OUTPUT_DIR = "ios/EdgeMemoryEngine/Models"
OUTPUT_PATH = os.path.join(OUTPUT_DIR, "BgeSmallEmbedding.mlpackage")
MAX_SEQ_LENGTH = 128

def export():
    print(f"Loading {MODEL_ID} from Hugging Face...")
    tokenizer = AutoTokenizer.from_pretrained(MODEL_ID)
    model = AutoModel.from_pretrained(MODEL_ID)
    model.eval()

    # Create dummy integer inputs matching the token sequence length
    dummy_input_ids = torch.randint(0, 1000, (1, MAX_SEQ_LENGTH), dtype=torch.int32)
    dummy_attention_mask = torch.ones((1, MAX_SEQ_LENGTH), dtype=torch.int32)

    class TorchWrapper(torch.nn.Module):
        def __init__(self, base_model):
            super().__init__()
            self.base_model = base_model

        def forward(self, input_ids):
            # Pass directly to transformer forward pass
            outputs = self.base_model(input_ids=input_ids)
            return outputs.last_hidden_state

    wrapped_model = TorchWrapper(model)
    traced_model = torch.jit.trace(wrapped_model, dummy_input_ids)

    print("Converting PyTorch trace to Core ML format...")
    mlmodel = ct.convert(
        traced_model,
        inputs=[
            ct.TensorType(
                name="input_ids",
                shape=(1, MAX_SEQ_LENGTH),
                dtype=int
            )
        ],
        outputs=[
            ct.TensorType(name="var_last_hidden_state")
        ],
        compute_units=ct.ComputeUnit.ALL,
        minimum_deployment_target=ct.target.iOS15
    )

    os.makedirs(OUTPUT_DIR, exist_ok=True)
    mlmodel.save(OUTPUT_PATH)
    print(f"Successfully generated Core ML package at: {OUTPUT_PATH}")

if __name__ == "__main__":
    export()
