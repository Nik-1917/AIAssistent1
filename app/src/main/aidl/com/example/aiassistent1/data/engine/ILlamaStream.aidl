package com.example.aiassistent1.data.engine;

interface ILlamaStream {
    void onDelta(String text);
}
