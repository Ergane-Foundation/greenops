package com.solstice.model;

public class ManagedJobStatus {

    private String name;
    private String state;
    private Integer priority;
    private String lastAction;
    private String lastSavepointPath;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public String getLastAction() {
        return lastAction;
    }

    public void setLastAction(String lastAction) {
        this.lastAction = lastAction;
    }

    public String getLastSavepointPath() {
        return lastSavepointPath;
    }

    public void setLastSavepointPath(String lastSavepointPath) {
        this.lastSavepointPath = lastSavepointPath;
    }
}
