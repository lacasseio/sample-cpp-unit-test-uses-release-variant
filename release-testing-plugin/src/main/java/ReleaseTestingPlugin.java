import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.FileCollectionDependency;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.cpp.CppApplication;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.CppSharedLibrary;
import org.gradle.language.cpp.CppStaticLibrary;
import org.gradle.language.cpp.ProductionCppComponent;
import org.gradle.language.nativeplatform.ComponentWithLinkUsage;
import org.gradle.language.nativeplatform.tasks.UnexportMainSymbol;
import org.gradle.nativeplatform.test.cpp.CppTestExecutable;
import org.gradle.nativeplatform.test.cpp.CppTestSuite;

import java.util.concurrent.Callable;

public /*final*/ abstract class ReleaseTestingPlugin implements Plugin<Project> {
    public abstract static class TestAgainstExtension {
        public abstract Property<TestedBinarySpec> getTestedBinarySpec();

        private enum TestedBinarySpec implements Spec<CppBinary> {
            RELEASE {
                @Override
                public boolean isSatisfiedBy(CppBinary cppBinary) {
                    return cppBinary.isOptimized();
                }
            },
            DEBUG {
                @Override
                public boolean isSatisfiedBy(CppBinary cppBinary) {
                    return !cppBinary.isOptimized();
                }
            }
            ;
        }

        public void release() {
            getTestedBinarySpec().set(TestedBinarySpec.RELEASE);
        }
    }

    @Override
    public void apply(Project project) {
        final TestAgainstExtension extension = project.getExtensions().create("testsAgainst", TestAgainstExtension.class);

        // Use to Gradle core default
        extension.getTestedBinarySpec().convention(TestAgainstExtension.TestedBinarySpec.DEBUG);

        project.getComponents().withType(CppTestSuite.class).configureEach(testSuite -> {
            testSuite.getBinaries().whenElementKnown(CppTestExecutable.class, new Action<>() {
                @Override
                public void execute(CppTestExecutable testBinary) {
                    //region nativeLink configuration
                    final Configuration linkConfiguration = project.getConfigurations().getByName("nativeLink" + capitalize(qualifyingName(testBinary)));
                    linkConfiguration.attributes(optimizedFrom(extension.getTestedBinarySpec()));
                    //endregion

                    //region cppCompile configuration
                    final Configuration compileConfiguration = project.getConfigurations().getByName("cppCompile" + capitalize(qualifyingName(testBinary)));
                    compileConfiguration.attributes(optimizedFrom(extension.getTestedBinarySpec()));
                    //endregion

                    //region nativeRuntime configuration
                    final Configuration runtimeConfiguration = project.getConfigurations().getByName("nativeRuntime" + capitalize(qualifyingName(testBinary)));
                    runtimeConfiguration.attributes(optimizedFrom(extension.getTestedBinarySpec()));
                    //endregion
                }

                private Action<AttributeContainer> optimizedFrom(Provider<TestAgainstExtension.TestedBinarySpec> testedSpec) {
                    return attributes -> attributes.attributeProvider(CppBinary.OPTIMIZED_ATTRIBUTE, testedSpec.map(TestAgainstExtension.TestedBinarySpec.RELEASE::equals));
                }
            });
            testSuite.getBinaries().whenElementKnown(CppTestExecutable.class, testBinary -> {
                // Note: we can't use testedComponent property because it's internal
                final ProductionCppComponent testedComponent = (ProductionCppComponent) project.getComponents().findByName("main");
                if (testedComponent == null) {
                    return; // nothing to do
                }

                testedComponent.getBinaries().whenElementFinalized((Action<CppBinary>) testedBinary -> {
                    if (!isTestedBinary(testBinary, testedComponent, testedBinary) || !extension.getTestedBinarySpec().get().isSatisfiedBy(testedBinary)) {
                        return;
                    }

                    // Recreate testable object file collection
                    final ConfigurableFileCollection testableObjects = project.getObjects().fileCollection();
                    if (testedComponent instanceof CppApplication) {
                        // In cases where the task `relocateMainFor*` doesn't exist (for some reason),
                        //   we can configure the task only when it appears (by name) using `withType(<type>).configureEach(if (<name>) ...).
                        //   That syntax replace `named(<name>, <type>, ...)`.
                        //   When wiring the value through `testableObjects` we can use a `Callable` to further defer the task query by name.
                        project.getTasks().withType(UnexportMainSymbol.class).configureEach(task -> {
                            if (task.getName().equals("relocateMainFor" + capitalize(qualifyingName(testBinary)))) {
                                task.getObjects().setFrom(testedBinary.getObjects());
                            }
                        });
                        testableObjects.from((Callable<?>) () -> {
                            final TaskProvider<UnexportMainSymbol> unexportMainSymbol = project.getTasks().named("relocateMainFor" + capitalize(qualifyingName(testBinary)), UnexportMainSymbol.class);
                            return unexportMainSymbol.map(UnexportMainSymbol::getRelocatedObjects);
                        });
                    } else {
                        testableObjects.from(testedBinary.getObjects());
                    }

                    final Configuration linkConfiguration = project.getConfigurations().getByName("nativeLink" + capitalize(qualifyingName(testBinary)));

                    // Assuming a single FileCollectionDependency which should be the Gradle core object files
                    //   In cases where this code executes *before* normal Gradle code (for some reason),
                    //   we can remove the Gradle (previous) testableObjects dependency to replace it with our own.
                    //   Note that we should normally be able to inspect the dependencies directly via:
                    //     linkConfiguration.getDependencies().removeIf(it -> it instanceof FileCollectionDependency);
                    //   Instead we remove any current and future, FileCollectionDependency that doesn't match our testableObjects.
                    linkConfiguration.getDependencies().all(it -> {
                        if (it instanceof FileCollectionDependency) {
                            if (!((FileCollectionDependency) it).getFiles().equals(testableObjects)) {
                                linkConfiguration.getDependencies().remove(it);
                            }
                        }
                    });
                    linkConfiguration.getDependencies().add(project.getDependencies().create(testableObjects));
                });
            });

        });
    }

    // Copied from Gradle core
    private static boolean isTestedBinary(CppTestExecutable testExecutable, ProductionCppComponent mainComponent, CppBinary testedBinary) {
        return testedBinary.getTargetMachine().getOperatingSystemFamily().getName().equals(testExecutable.getTargetMachine().getOperatingSystemFamily().getName())
                && testedBinary.getTargetMachine().getArchitecture().getName().equals(testExecutable.getTargetMachine().getArchitecture().getName())
//                && !testedBinary.isOptimized() // use TestAgainstExtension
                && hasDevelopmentBinaryLinkage(mainComponent, testedBinary);
    }


    // From Gradle core
    private static boolean hasDevelopmentBinaryLinkage(ProductionCppComponent mainComponent, CppBinary testedBinary) {
        if (!(testedBinary instanceof ComponentWithLinkUsage)) {
            return true;
        }
        ComponentWithLinkUsage developmentBinaryWithUsage = (ComponentWithLinkUsage) mainComponent.getDevelopmentBinary().get();
        ComponentWithLinkUsage testedBinaryWithUsage = (ComponentWithLinkUsage) testedBinary;
        if (testedBinaryWithUsage instanceof CppSharedLibrary && developmentBinaryWithUsage instanceof CppSharedLibrary) {
            return true;
        } else if (testedBinaryWithUsage instanceof CppStaticLibrary && developmentBinaryWithUsage instanceof CppStaticLibrary) {
            return true;
        } else {
            return false;
        }
    }

    //region Names
    private static String qualifyingName(CppTestExecutable binary) {
        // The binary name follow the pattern <componentName><variantName>Executable
        return binary.getName().substring(0, binary.getName().length() - "Executable".length());
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
    //endregion
}
