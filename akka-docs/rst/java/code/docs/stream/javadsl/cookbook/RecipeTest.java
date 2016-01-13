package docs.stream.javadsl.cookbook;

public class RecipeTest {
  final class Message {
    public final String msg;

    public Message(String msg) {
      this.msg = msg;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      Message message = (Message) o;

      if (msg != null ? !msg.equals(message.msg) : message.msg != null) {
        return false;
      }

      return true;
    }

    @Override
    public int hashCode() {
      return msg != null ? msg.hashCode() : 0;
    }
  }

  final class Trigger {
  }

  final class Job {
  }
}
