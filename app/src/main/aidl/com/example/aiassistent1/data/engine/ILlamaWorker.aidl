package com.example.aiassistent1.data.engine;

import android.os.Bundle;
import com.example.aiassistent1.data.engine.ILlamaStream;

interface ILlamaWorker {
    Bundle execute(String owner, String operation, in Bundle arguments, ILlamaStream stream);
    oneway void cancel(String owner);
    oneway void abort(String owner);
}
