import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Read-only ART shell harness. Uses exact application DEX and JNI, no app install,
 * microphone, settings, database or enrollment. Input is public test fixtures only. */
public final class MetricKwsParityMain {
    private static String sha(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder();
        for (byte b : hash) out.append(String.format("%02x", b & 255));
        return out.toString();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("directory, expected model SHA");
        Path root = Paths.get(args[0]);
        byte[] model = Files.readAllBytes(root.resolve("encoder.onnx"));
        if (!sha(model).equals(args[1])) throw new IllegalStateException("Model SHA mismatch");
        Class<?> extractorClass = Class.forName("com.example.aiassistent1.data.provider.MetricKwsFeatureExtractor");
        Object extractor = extractorClass.getConstructor().newInstance();
        Method extract = extractorClass.getMethod("extract", float[].class, int.class);
        Class<?> bridgeClass = Class.forName("com.example.aiassistent1.data.provider.MetricKwsNative");
        Object bridge = bridgeClass.getField("INSTANCE").get(null);
        Method create = bridgeClass.getMethod("create", byte[].class);
        Method infer = bridgeClass.getMethod("infer", long.class, float[].class);
        Method close = bridgeClass.getMethod("close", long.class);
        Class<?> embeddingClass = Class.forName("com.example.aiassistent1.domain.model.KeywordEmbedding");
        Object embedding = embeddingClass.getField("INSTANCE").get(null);
        Method normalize = embeddingClass.getMethod("normalize", float[].class);
        long handle = (Long) create.invoke(bridge, (Object) model);
        double maximum = 0., minimumCosine = 1.;
        int cases = 0;
        List<Double> elapsed = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(root.resolve("cases.tsv"))) {
                String[] cells = line.split("\t");
                if (cells.length != 66) throw new IllegalStateException("Bad case");
                byte[] bytes = Files.readAllBytes(root.resolve(cells[0]));
                if (!sha(bytes).equals(cells[1])) throw new IllegalStateException("Fixture SHA mismatch");
                ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                if (buffer.getInt() != 0x31464b4d) throw new IllegalStateException("Bad fixture");
                int count = buffer.getInt();
                if (count < 8000 || count > 128000 || buffer.getInt() != 4040)
                    throw new IllegalStateException("Bad fixture dimensions");
                float[] pcm = new float[count];
                for (int i = 0; i < count; i++) pcm[i] = buffer.getFloat();
                float[] original = pcm.clone();
                long started = System.nanoTime();
                float[] features = (float[]) extract.invoke(extractor, pcm, 16000);
                float[] raw = (float[]) infer.invoke(bridge, handle, features);
                float[] actual = (float[]) normalize.invoke(embedding, (Object) raw);
                elapsed.add((System.nanoTime() - started) / 1e6);
                if (!Arrays.equals(pcm, original) || actual.length != 64)
                    throw new IllegalStateException("Changed PCM or embedding dimensions");
                double dot = 0., aa = 0., bb = 0.;
                for (int i = 0; i < 64; i++) {
                    double expected = Double.parseDouble(cells[i + 2]);
                    if (!Float.isFinite(actual[i])) throw new IllegalStateException("Nonfinite embedding");
                    maximum = Math.max(maximum, Math.abs(expected - actual[i]));
                    dot += expected * actual[i]; aa += expected * expected; bb += (double) actual[i] * actual[i];
                }
                minimumCosine = Math.min(minimumCosine, dot / Math.sqrt(aa * bb));
                cases++;
            }
        } finally {
            close.invoke(bridge, handle);
        }
        if (cases != 41 || maximum > 1e-4 || minimumCosine < .9999)
            throw new IllegalStateException("Parity failed: " + cases + " " + maximum + " " + minimumCosine);
        Collections.sort(elapsed);
        System.out.println("{\"cases\":" + cases + ",\"max_absolute_error\":" + maximum +
            ",\"minimum_cosine\":" + minimumCosine + ",\"ort\":\"" +
            bridgeClass.getMethod("runtimeVersion").invoke(bridge) +
            "\",\"pipeline_ms_median\":" + elapsed.get(elapsed.size() / 2) +
            ",\"pipeline_ms_max\":" + elapsed.get(elapsed.size() - 1) +
            ",\"scope\":\"Physical ARM ART shell, exact app DEX/JNI; public PCM, no microphone/app lifecycle test\"}");
    }
}
