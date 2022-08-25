package lox;

public class LoxCaptured {
    private Object value;

    public LoxCaptured(Object value) {
        this.value = value;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return "<captured " + this.value.toString() + ">";
    }
}
