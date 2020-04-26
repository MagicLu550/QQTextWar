package cn.textwar.utils.test;

import cn.qqtextwar.Server;
import cn.qqtextwar.api.Application;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TextWarServerRunner {

    private List<Method> tests = new ArrayList<>();

    private List<Method> befores = new ArrayList<>();

    private List<Method> afters = new ArrayList<>();

    private List<Application> clients = new ArrayList<>();

    private TextWarServerRunner runner;


    public TextWarServerRunner() {
        this.runner = this;
        Arrays.stream(this.getClass().getDeclaredMethods()).forEach(
                x->{
                    if(x.getAnnotation(TextWarBefore.class)!=null){
                        befores.add(x);
                    }
                    if(x.getAnnotation(TextWarServerTest.class) != null){
                        tests.add(x);
                    }
                    if(x.getAnnotation(TextWarAfter.class)!=null){
                        afters.add(x);
                    }
                }
        );
    }

    public static void doTest(TextWarServerRunner runner){
        runner.doTest();
    }

    public void doTest(){
        if(runner != null){
            Arrays.stream(this.getClass().getAnnotation(Client.class).value()).forEach(
                    x->{
                        try {
                            clients.add(x.newInstance());
                        }catch (Exception e){
                            e.printStackTrace();
                        }
                    }
            );
            Server.start(clients.toArray(new Application[0]));
            invokeAll(befores,TextWarBefore.class);
            invokeAll(tests,TextWarServerTest.class);
            invokeAll(afters,TextWarAfter.class);
        }

    }

    public void invokeAll(List<Method> methods, Class<? extends Annotation> test){
        methods.forEach(x->{
            try {
                if ((boolean)test.getDeclaredMethod("doIt").invoke(x.getAnnotation(test))) {
                    if (x.getParameterTypes().length == 1 && x.getParameterTypes()[0].equals(Server.class)) {
                        x.invoke(runner,Server.getServer());
                    } else {
                        x.invoke(runner);
                    }
                }
            }catch (Exception e){
                e.printStackTrace();
            }
        });
    }
}
