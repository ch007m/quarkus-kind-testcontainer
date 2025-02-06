package org.acme;

import com.dajudge.kindcontainer.KindContainer;

public class BaseContainer {
    static final KindContainer KIND;

    static {
        KIND = new KindContainer();
        KIND.start();
    }
}
