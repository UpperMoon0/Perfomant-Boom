import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Checks the actual packaged jars for the split-package failure Forge hits at startup. */
public class CheckForgeModules {
    public static void main(String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException("Pass the Boom jar and companion mod jars");
        }
        var finder = ModuleFinder.of(Arrays.stream(args).map(Path::of).toArray(Path[]::new));
        var roots = finder.findAll().stream()
                .map(module -> module.descriptor().name()).collect(Collectors.toSet());
        Configuration.empty().resolveAndBind(finder, ModuleFinder.ofSystem(), roots);
        System.out.println("Module resolution passed: " + roots);
    }
}
