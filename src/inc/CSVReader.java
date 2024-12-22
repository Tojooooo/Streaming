package inc;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class CSVReader {

    // Function to read CSV and convert to String[][]
    public static String[][] readCSV(String filePath, String separator, String endLine) {
        List<String[]> rows = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;

            while ((line = br.readLine()) != null) {
                String[] temp = line.split(separator);
                temp[temp.length-1] = temp[temp.length-1].split(endLine)[0];
                rows.add(temp);
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        // Convert List<String[]> to String[][] for final result
        return rows.toArray(new String[0][]);
    }

    public static void importConfig (String path, Object obj) {
        String[][] data = CSVReader.readCSV(path, "=", ";");
        // show all the attributes with their respective values
        for (int i = 0; i < data.length; i++) {
            for (int j = 0; j < data[i].length; j++) {
                System.out.print(data[i][j] +" ");
            }
            System.out.println();
        }
        System.out.println();
        Field[] listFields = obj.getClass().getDeclaredFields(); // take the attributes as Fields

        for (int i = 0; i < data.length; i++) { // take each attribute on each line of the file
            int fieldIndex; // index of the actual attribute
            for (fieldIndex = 0; fieldIndex < listFields.length; fieldIndex++) {
                if (listFields[fieldIndex].getName().equals(data[i][0])) {
                    break;
                }
            }
            if (fieldIndex == listFields.length) { // case : attribute not found in the class
                System.out.println("Warning: Field " + data[i][0] + " not found in the class.");
            } else {
                try {
                    listFields[fieldIndex].setAccessible(true);
                    String fieldType = listFields[fieldIndex].getType().getSimpleName().toLowerCase();
                    String fieldName = data[i][0];

                    if (fieldType.endsWith("[]")) { // specific allocation for arrays
                        String elementType = fieldType.substring(0, fieldType.length()-2).toLowerCase();
                        String[] listValues = data[i][1].split(",");
                        switch (elementType) {
                            case "string" -> listFields[fieldIndex].set(obj, listValues);
                            case "int", "integer" -> {
                                int[] intArray = new int[listValues.length];
                                for (int j = 0; j < listValues.length; j++) {
                                    intArray[j] = Integer.parseInt(listValues[j]);
                                }
                                listFields[fieldIndex].set(obj, intArray);
                            }
                            case "double" -> {
                                double[] doubleArray = new double[listValues.length];
                                for (int j = 0; j < listValues.length; j++) {
                                    doubleArray[j] = Double.parseDouble(listValues[j]);
                                }
                                listFields[fieldIndex].set(obj, doubleArray);
                            }
                            case "float" -> {
                                float[] floatArray = new float[listValues.length];
                                for (int j = 0; j < listValues.length; j++) {
                                    floatArray[j] = Float.parseFloat(listValues[j]);
                                }
                                listFields[fieldIndex].set(obj, floatArray);
                            }
                            case "boolean" -> {
                                boolean[] booleanArray = new boolean[listValues.length];
                                for (int j = 0; j < listValues.length; j++) {
                                    booleanArray[j] = Boolean.parseBoolean(listValues[j]);
                                }
                                listFields[fieldIndex].set(obj, booleanArray);
                            }
                        }

                    } else {
                        switch (fieldType) { // set the given value of each attribute according to its type
                            case "int", "integer" -> listFields[fieldIndex].set(obj, Integer.parseInt(data[i][1]));
                            case "double" -> listFields[fieldIndex].set(obj, Double.parseDouble(data[i][1]));
                            case "float" -> listFields[fieldIndex].set(obj, Float.parseFloat(data[i][1]));
                            case "string" -> listFields[fieldIndex].set(obj, data[i][1]);
                            case "boolean" -> listFields[fieldIndex].set(obj, Boolean.parseBoolean(data[i][1]));
                        }
                    }

                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }

        }

    }
}