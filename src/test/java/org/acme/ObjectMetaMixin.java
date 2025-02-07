package org.acme;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.fabric8.kubernetes.api.model.ManagedFieldsEntry;
import io.fabric8.kubernetes.api.model.ObjectMeta;

import java.util.List;

@SuppressWarnings("unused")
public abstract class ObjectMetaMixin extends ObjectMeta {

    @JsonIgnore
    private List<ManagedFieldsEntry> managedFields;

    @JsonIgnore
    public abstract List<ManagedFieldsEntry> getManagedFields();

}
