package peergos.server.tests;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import peergos.server.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

import static peergos.server.tests.UserTests.ensureSignedUp;

@RunWith(Parameterized.class)
public class QuotaTests {

    public static int RANDOM_SEED = 666;
    private final NetworkAccess network;
    private final Crypto crypto = Crypto.initJava();

    private static Random random = new Random(RANDOM_SEED);

    public QuotaTests(String useIPFS, Random r) throws Exception {
        int portMin = 9000;
        int portRange = 4000;
        int webPort = portMin + r.nextInt(portRange);
        int corePort = portMin + portRange + r.nextInt(portRange);
        Args args = Args.parse(new String[]{
                "useIPFS", "" + useIPFS.equals("IPFS"),
                "-port", Integer.toString(webPort),
                "-corenodePort", Integer.toString(corePort),
                "default-quota", Long.toString(2 * 1024 * 1024)
        });
        Start.LOCAL.main(args);
        this.network = NetworkAccess.buildJava(new URL("http://localhost:" + webPort)).get();
        // use insecure random otherwise tests take ages
        setFinalStatic(TweetNaCl.class.getDeclaredField("prng"), new Random(1));
    }

    @Parameterized.Parameters()
    public static Collection<Object[]> parameters() {
        return Arrays.asList(new Object[][]{
                {"IPFS", new Random(0)}
        });
    }

    static void setFinalStatic(Field field, Object newValue) throws Exception {
        field.setAccessible(true);

        Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);

        field.set(null, newValue);
    }

    private String generateUsername() {
        return "test" + Math.abs(random.nextInt() % 10000);
    }

    @Test
    public void quota() throws Exception {
        String username = generateUsername();
        String password = "badpassword";

        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode home = context.getByPath(Paths.get(username).toString()).get().get();
        byte[] data = new byte[1024*1024];
        random.nextBytes(data);
        FileTreeNode newHome = home.uploadFile("file-1", new AsyncReader.ArrayBacked(data), data.length,
                network, crypto.random, x -> { }, context.fragmenter()).get();

        try {
            newHome.uploadFile("file-2", new AsyncReader.ArrayBacked(data), data.length, network, crypto.random, x -> {
            }, context.fragmenter()).get();
            Assert.fail("Quota wasn't enforced");
        } catch (Exception e) {}
    }

    @Test
    public void deletionsReduceUsage() throws Exception {
        String username = generateUsername();
        String password = "badpassword";

        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode home = context.getByPath(Paths.get(username).toString()).get().get();
        byte[] data = new byte[1024 * 1024];
        random.nextBytes(data);
        for (int i=0; i < 5; i++) {
            String filename = "file-1";
            home = home.uploadFile(filename, new AsyncReader.ArrayBacked(data), data.length,
                    network, crypto.random, x -> {}, context.fragmenter()).get();
            FileTreeNode file = context.getByPath("/" + username + "/" + filename).get().get();
            home = file.remove(network, home).get();
        }
    }

    @Test
    public void deletionAtQuota() throws Exception {
        String username = generateUsername();
        String password = "badpassword";

        UserContext context = ensureSignedUp(username, password, network, crypto);
        FileTreeNode home = context.getByPath(Paths.get(username).toString()).get().get();
        // signing up uses just over 4k and the quota is 2 MiB, so use within 1 KiB of our quota
        byte[] data = new byte[2 * 1024 * 1024 - 5 * 1024];
        random.nextBytes(data);
        String filename = "file-1";
        home = home.uploadFile(filename, new AsyncReader.ArrayBacked(data), data.length,
                network, crypto.random, x -> {}, context.fragmenter()).get();
        FileTreeNode file = context.getByPath("/" + username + "/" + filename).get().get();
        file.remove(network, home).get();
    }
}
