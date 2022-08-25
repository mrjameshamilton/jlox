package lox;

public abstract class LoxMethod extends LoxFunction implements Cloneable {

    private LoxInstance $this;


    public LoxMethod(LoxClass loxClass) {
        super(loxClass);
    }

    public LoxClass getLoxClass() {
        return (LoxClass) getEnclosing();
    }

    public LoxMethod bind(LoxInstance loxInstance) {
        LoxMethod clone;
        try {
            clone = (LoxMethod) this.clone();
        } catch (CloneNotSupportedException e) {
            throw new LoxException("Could not bind " + this);
        }

        clone.$this = loxInstance;
        return clone;
    }

    public LoxInstance getReceiver() {
        return $this;
    }
}
