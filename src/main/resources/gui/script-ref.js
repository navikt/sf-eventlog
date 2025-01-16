document.addEventListener("DOMContentLoaded", function () {
    const loadingSpinner = document.getElementById("loading");
    const metadataContainer = document.getElementById("metadata-container");

    // Show loading spinner
    loadingSpinner.style.display = "block";

    const projectTitleElement = document.getElementById("project-title");

    // Fetch metadata
    fetch('/internal/metadata')
        .then(response => {
            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }
            return response.json();
        })
        .then(data => {
            loadingSpinner.style.display = "none"; // Hide loading spinner
            renderMetadata(data); // Render metadata
            //highlightUnmappedFields();
        })
        .catch(error => {
            loadingSpinner.style.display = "none"; // Hide loading spinner
            metadataContainer.innerHTML = `<p style="color:red;">Failed to load metadata: ${error.message}</p>`;
        });

    function renderMetadata(metadata) {
        metadataContainer.innerHTML = ''; // Clear any existing content

        // Create dataset section
        const datasetSection = document.createElement("div");
        datasetSection.classList.add("dataset-section");

        // Dataset header
        const datasetHeader = document.createElement("div");
        datasetHeader.classList.add("dataset-header");
        datasetHeader.textContent = "";
        datasetSection.appendChild(datasetHeader);

        Object.keys(metadata).forEach(eventType => {
            // Create table container
            const tableDiv = document.createElement("div");
            tableDiv.classList.add("table");

            // Table header (expandable bar)
            const tableHeader = document.createElement("div");
            tableHeader.classList.add("table-header"); // Add styles for the header container

            // Wrapper div to group table name and the "inactive" label together
            const nameAndLabelWrapper = document.createElement("div");
            nameAndLabelWrapper.classList.add("name-and-label-wrapper");

            // Table name
            const tableNameDiv = document.createElement("div");
            tableNameDiv.textContent = eventType;

            // Create the 'inactive' label div
            const inactiveLabel = document.createElement("div");
            inactiveLabel.textContent = 'INACTIVE';
            inactiveLabel.classList.add('inactive-label');
            inactiveLabel.title = "No logs will be transfered for this event type";

            // Append the table name and inactive label into the wrapper
            nameAndLabelWrapper.appendChild(tableNameDiv);
            //TODO add active to model
            //if (!table.active) {
            //    nameAndLabelWrapper.appendChild(inactiveLabel);
            //}

            // Create a separate div for the row count
            const rowCountDiv = document.createElement("div");
            rowCountDiv.classList.add("row-count");
            //rowCountDiv.textContent = `${table.numRows} records`;

            // Append the wrapper and row count div to the table header
            tableHeader.appendChild(nameAndLabelWrapper);
            tableHeader.appendChild(rowCountDiv);


            // Add the header to the table div
            tableDiv.appendChild(tableHeader);

            // Container for table details NEW
            const tableDetails = document.createElement("div");
            tableDetails.classList.add("table-details");
            tableDetails.style.display = "none"; // Hidden by default

            // Table columns (hidden by default)
            const tableColumns = document.createElement("div");
            tableColumns.classList.add("table-columns");

            const tableColumnsTable = document.createElement("table");

            // Add header row
            const headerRow = document.createElement("tr");

            ["Logfile Date", "Status", "", "Message", "Last modified"].forEach(headerText => {
                const th = document.createElement("th");
                th.textContent = headerText;
                headerRow.appendChild(th);
                //if (headerText === "Status") {
                    //th.style.textAlign = "center"
                //}
            });
            tableColumnsTable.appendChild(headerRow);

            const logStatusRows = metadata[eventType]

            logStatusRows.forEach(logStatusRow => {
                const row = document.createElement("tr");
                ["syncDate", "status", "", "message", "lastModified"].forEach(key => {
                    const td = document.createElement("td");
                    if (key === "status") {
                        const statusSpan = document.createElement('span');
                        statusSpan.textContent = logStatusRow[key];
                        statusSpan.classList.add(logStatusRow[key]);
                        td.appendChild(statusSpan)
                        // Check if the status is "FAILURE" and add the class to the row
                        if (logStatusRow[key] === "FAILURE") {
                            row.classList.add("failedRow");
                        }
                    } else if (key === "" && (logStatusRow["status"] === "UNPROCESSED" || logStatusRow["status"] === "FAILURE")) {
                        // Add button to the third column (Message) for UNPROCESSED or FAILURE statuses
                        const button = document.createElement("button");
                        button.textContent = "Fetch";
                        button.classList.add("fetch-button");
                        button.onclick = () => {
                            // Find the next sibling cell (message column)
                            const messageCell = td.nextElementSibling;
                            const statusCell = td.previousElementSibling;
                            const lastModifiedCell = messageCell.nextElementSibling;
                            if (messageCell) {
                                // Clear the cell content
                                messageCell.textContent = "";

                                // Create and add the spinner
                                const spinner = document.createElement("div");
                                spinner.classList.add("small-spinner");
                                spinner.style.display="block";
                                messageCell.appendChild(spinner);
                                button.style.display="none";

                                fetch('/internal/fetchAndLog?date=' + logStatusRow["syncDate"] + '&eventType=' + logStatusRow["eventType"], {
                                    method: "GET",
                                })
                                    .then(response => {
                                        if (response.ok) {
                                            // Parse as JSON for successful responses
                                            return response.json().then(data => ({
                                                status: response.status,
                                                body: data
                                            }));
                                        } else {
                                            // Handle non-200 responses as plain text
                                            return response.text().then(text => ({
                                                status: response.status,
                                                body: text
                                            }));
                                        }
                                    })
                                    .then(({ status, body }) => {
                                        spinner.style.display = "none";
                                        if (status === 200) {
                                            const statusSpan = document.createElement('span');
                                            statusSpan.textContent = body["status"];
                                            statusSpan.classList.add(body["status"]);
                                            statusCell.innerHTML = ""
                                            statusCell.appendChild(statusSpan)
                                            messageCell.textContent = body["message"]
                                            if (body["status"] !== "NO_LOGFILE") {
                                                lastModifiedCell.textContent = body["lastModified"]
                                            }
                                            // Check if the status is "FAILURE" and add the class to the row
                                            if (body["status"] === "FAILURE") {
                                                row.classList.add("failedRow");
                                                button.style.display="block";
                                            } else {
                                                row.classList.remove("failedRow");
                                            }
                                        } else {
                                            button.style.display="block";
                                            row.classList.add("failedRow");
                                            messageCell.textContent = `Fail: ${body}`;
                                        }
                                    })
                                    .catch(err => {
                                        button.style.display="block";
                                        spinner.style.display = "none";
                                        row.classList.add("failedRow");
                                        messageCell.textContent = `Fail: ${err.message}`;
                                    });
                            }
                        };
                        td.appendChild(button);
                        td.style.width = "50px";
                        td.style.padding = "4px";
                    } else if (key === "") {
                        td.textContent = ""
                        td.style.width = "50px";
                    } else if (key === "lastModified" && (logStatusRow["status"] === "UNPROCESSED" || logStatusRow["status"] === "NO_LOGFILE")) {
                        td.textContent = ""
                    } else {
                        td.textContent = logStatusRow[key];
                    }

                    row.appendChild(td);
                });

                // Check if syncDate is older than 5 days
                const syncDate = new Date(logStatusRow.syncDate); // Convert string to Date
                const today = new Date(); // Get today's date
                const thirtyDaysAgo = new Date(today);
                thirtyDaysAgo.setDate(today.getDate() - 30); // Calculate the date 5 days ago

                if (syncDate < thirtyDaysAgo) {
                    row.classList.add("oldrow"); // Add the class oldrow
                }

                tableColumnsTable.appendChild(row);
            });

            tableColumns.appendChild(tableColumnsTable);
            tableDetails.appendChild(tableColumns);
            tableDiv.appendChild(tableDetails);

            // Toggle visibility of columns on header click
            tableHeader.addEventListener("click", function () {
                if (tableDetails.style.display === "none" || !tableDetails.style.display) {
                    tableDetails.style.display = "block";
                } else {
                    tableDetails.style.display = "none";
                }
            });

            datasetSection.appendChild(tableDiv)

            metadataContainer.appendChild(datasetSection);
        })
    }
})
