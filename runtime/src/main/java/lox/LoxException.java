package lox;

/**
 * Represents a Lox runtime exception.
 */
public class LoxException extends RuntimeException {

    private final int line;

    public LoxException(String message) {
        super(message);
        this.line = -1;
    }

    public LoxException(String message, int line) {
        super(message);
        this.line = line;
    }

    @Override
    public String getMessage() {
        return super.getMessage() + "\n[line " + getLine() + "]";
    }

    public int getLine() {
        if (this.line != -1) return this.line;

        int line = -1;
        // Skip over the "lox.**" internal runtime classes.
        for (int i = 0; i < getStackTrace().length; i++) {
            if (!getStackTrace()[i].getClassName().startsWith("lox.") && getStackTrace()[i].getLineNumber() != -1) {
                line = getStackTrace()[i].getLineNumber();
                break;
            }
        }
        return line;
    }
}
